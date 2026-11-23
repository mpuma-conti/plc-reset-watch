package pe.tesla.plcreset;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reset PLC para Wear OS.
 * - Pide a n8n la lista de motores ({"accion":"listar"}).
 * - Mantener pulsado un motor HOLD_MS envía {"accion":"reset","motor":"MC213"}.
 * - Muestra la respuesta de n8n: {status, motor, titulo, detalle, hora}.
 */
public class MainActivity extends Activity {

    private static final long HOLD_MS = 1500;          // tiempo de pulsación para confirmar
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;  // n8n espera hasta 40 s a plc-reset

    private static final int C_FONDO = Color.BLACK;
    private static final int C_BOTON = Color.parseColor("#2A2F36");
    private static final int C_RELLENO = Color.parseColor("#C62828");
    private static final int C_TEXTO = Color.WHITE;
    private static final int C_GRIS = Color.parseColor("#9AA0A6");
    private static final int C_OK = Color.parseColor("#66BB6A");
    private static final int C_INFO = Color.parseColor("#42A5F5");
    private static final int C_ERROR = Color.parseColor("#EF5350");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ScrollView scroll;
    private LinearLayout root;
    private JSONArray ultimaLista;   // lista en memoria para "Volver" sin pedirla otra vez
    private boolean ocupado = false;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_FONDO);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        // Márgenes generosos: la pantalla es redonda
        root.setPadding(w * 12 / 100, h * 14 / 100, w * 12 / 100, h * 22 / 100);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        cargarLista();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ pantallas

    private void cargarLista() {
        pantallaMensaje("Cargando motores…", null);
        io.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("accion", "listar");
                JSONObject r = post(body);
                JSONArray motores = r.optJSONArray("motores");
                if (motores == null) throw new IOException("Respuesta sin 'motores': " + recortar(r.toString(), 120));
                ui.post(() -> {
                    ultimaLista = motores;
                    pantallaLista(motores);
                });
            } catch (Exception e) {
                ui.post(() -> pantallaErrorLista(describir(e)));
            }
        });
    }

    private void pantallaLista(JSONArray motores) {
        root.removeAllViews();
        root.addView(texto("Reset PLC", 15, C_GRIS, true));
        root.addView(texto("Mantén pulsado para resetear", 12, C_GRIS, false));
        espacio(8);

        for (int i = 0; i < motores.length(); i++) {
            JSONObject m = motores.optJSONObject(i);
            if (m == null) continue;
            String id = m.optString("id", "");
            String desc = m.optString("desc", id);
            if (id.isEmpty()) continue;
            root.addView(botonMantener(id, desc));
        }

        espacio(10);
        root.addView(botonSimple("Actualizar", this::cargarLista));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void pantallaErrorLista(String detalle) {
        root.removeAllViews();
        root.addView(texto("❌", 32, C_ERROR, false));
        root.addView(texto("No se pudo cargar la lista", 15, C_ERROR, true));
        root.addView(texto(detalle, 12, C_GRIS, false));
        espacio(10);
        root.addView(botonSimple("Reintentar", this::cargarLista));
    }

    private void pantallaMensaje(String titulo, String sub) {
        root.removeAllViews();
        espacio(30);
        root.addView(texto(titulo, 16, C_TEXTO, true));
        if (sub != null) root.addView(texto(sub, 14, C_GRIS, false));
    }

    private void ejecutarReset(String motor) {
        if (ocupado) return;
        ocupado = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        vibrar(new long[]{0, 40});
        pantallaMensaje("Enviando reset…", motor);

        io.execute(() -> {
            String status, titulo, detalle, hora = "", nombre = motor;
            try {
                JSONObject body = new JSONObject().put("accion", "reset").put("motor", motor);
                JSONObject r = post(body);
                status = r.optString("status", "?");
                nombre = r.optString("motor", motor);
                titulo = r.optString("titulo", status);
                detalle = r.optString("detalle", "");
                hora = r.optString("hora", "");
            } catch (Exception e) {
                // Sin respuesta: el reset PUDO haberse ejecutado igual
                status = "error";
                titulo = "Sin respuesta";
                detalle = describir(e) + "\n\nNo se sabe si el reset se ejecutó. Revisa Telegram o n8n antes de reintentar.";
            }
            final String fs = status, ft = titulo, fd = detalle, fh = hora, fn = nombre;
            ui.post(() -> pantallaResultado(fs, fn, ft, fd, fh));
        });
    }

    private void pantallaResultado(String status, String motor, String titulo, String detalle, String hora) {
        ocupado = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String icono;
        int color;
        long[] patron;
        switch (status) {
            case "ok":      icono = "✅"; color = C_OK;    patron = new long[]{0, 80, 120, 80}; break;
            case "skipped": icono = "ℹ️"; color = C_INFO;  patron = new long[]{0, 120};          break;
            default:        icono = "❌"; color = C_ERROR; patron = new long[]{0, 500};          break;
        }
        vibrar(patron);

        root.removeAllViews();
        root.addView(texto(icono, 32, color, false));
        root.addView(texto(motor, 18, C_TEXTO, true));
        root.addView(texto(titulo, 15, color, true));
        espacio(4);
        if (!detalle.isEmpty()) root.addView(texto(detalle, 12, C_GRIS, false));
        if (!hora.isEmpty()) root.addView(texto("🕒 " + hora, 12, C_GRIS, false));
        espacio(10);
        root.addView(botonSimple("Volver", () -> {
            if (ultimaLista != null) pantallaLista(ultimaLista);
            else cargarLista();
        }));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    // ------------------------------------------------------------------ red

    private JSONObject post(JSONObject body) throws Exception {
        String url = BuildConfig.N8N_URL;
        if (url == null || url.isEmpty()) throw new IllegalStateException("App compilada sin N8N_URL");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-Api-Key", BuildConfig.N8N_API_KEY);

            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(data);
            }

            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = leer(is);
            if (code != 200) {
                String extra = text.isEmpty() ? "" : ": " + recortar(text, 100);
                if (code == 401 || code == 403) throw new IOException("Clave rechazada (HTTP " + code + ")");
                if (code == 404) throw new IOException("Webhook no encontrado (HTTP 404). ¿Flujo activo?");
                throw new IOException("HTTP " + code + extra);
            }
            return new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String leer(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8").trim();
        }
    }

    private static String describir(Exception e) {
        if (e instanceof SocketTimeoutException) return "Tiempo de espera agotado";
        if (e instanceof java.net.UnknownHostException) return "Sin conexión a internet (o DNS)";
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------ vistas

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView texto(String s, int sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (negrita) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private void espacio(int dpAlto) {
        View v = new View(this);
        root.addView(v, new LinearLayout.LayoutParams(1, dp(dpAlto)));
    }

    private GradientDrawable redondeado(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(26));
        return g;
    }

    private LinearLayout.LayoutParams paramsBoton() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        return lp;
    }

    /** Botón que se rellena mientras se mantiene pulsado; al completarse ejecuta el reset. */
    private View botonMantener(String id, String desc) {
        TextView b = new TextView(this);
        b.setText(desc.equals(id) ? id : id + "\n" + desc);
        b.setTextSize(17);
        b.setTextColor(C_TEXTO);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(56));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));

        ClipDrawable relleno = new ClipDrawable(redondeado(C_RELLENO), Gravity.LEFT, ClipDrawable.HORIZONTAL);
        relleno.setLevel(0);
        b.setBackground(new LayerDrawable(new Drawable[]{redondeado(C_BOTON), relleno}));
        b.setLayoutParams(paramsBoton());
        b.setOnTouchListener(new Mantener(relleno, () -> ejecutarReset(id)));
        return b;
    }

    private View botonSimple(String s, Runnable accion) {
        TextView b = new TextView(this);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(C_TEXTO);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(redondeado(Color.parseColor("#3C4043")));
        b.setLayoutParams(paramsBoton());
        b.setOnClickListener(v -> {
            if (!ocupado) accion.run();
        });
        return b;
    }

    /** Lógica de "mantener pulsado": soltar antes de tiempo o desplazar la lista cancela. */
    private class Mantener implements View.OnTouchListener {
        private final ClipDrawable relleno;
        private final Runnable accion;
        private ValueAnimator anim;
        private boolean cancelado;
        private boolean disparado;

        Mantener(ClipDrawable relleno, Runnable accion) {
            this.relleno = relleno;
            this.accion = accion;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (ocupado) return false;
                    cancelado = false;
                    disparado = false;
                    anim = ValueAnimator.ofInt(0, 10000);
                    anim.setDuration(HOLD_MS);
                    anim.addUpdateListener(a -> relleno.setLevel((Integer) a.getAnimatedValue()));
                    anim.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationCancel(Animator a) {
                            cancelado = true;
                        }

                        @Override
                        public void onAnimationEnd(Animator a) {
                            if (!cancelado) {
                                disparado = true;
                                relleno.setLevel(0);
                                accion.run();
                            }
                        }
                    });
                    anim.start();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (anim != null && !disparado) anim.cancel();
                    relleno.setLevel(0);
                    return true;

                default:
                    return true;
            }
        }
    }

    private void vibrar(long[] patron) {
        try {
            Vibrator vib = getSystemService(Vibrator.class);
            if (vib != null && vib.hasVibrator()) vib.vibrate(VibrationEffect.createWaveform(patron, -1));
        } catch (Exception ignored) {
            // sin vibración no pasa nada
        }
    }
}
