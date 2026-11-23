# Reset PLC — app para Wear OS (Galaxy Watch8)

App personal de un solo propósito: lista los motores que define el flujo de n8n
y, manteniendo pulsado uno 1,5 s, envía el reset y muestra la respuesta.

- La lista de motores viene de n8n (`{"accion":"listar"}`): para agregar un motor
  se edita el nodo **Resolver motor** en n8n, no hace falta recompilar la app.
- La URL y la clave del webhook se inyectan al compilar desde los *secrets* del
  repositorio (`N8N_URL`, `N8N_API_KEY`). No están en el código.
- GitHub Actions compila el APK en cada push a `main` (o a mano: Actions → Compilar APK → Run workflow).

## Archivos

| Archivo | Qué es |
|---|---|
| `app/src/main/java/pe/tesla/plcreset/MainActivity.java` | Toda la app |
| `app/build.gradle` | Configuración de compilación |
| `app/signing.keystore` | Firma fija, para actualizar con `adb install -r` sin desinstalar |
| `.github/workflows/build.yml` | Compilación en GitHub Actions |

## Instalar / actualizar en el reloj (Windows, PowerShell, desde la carpeta de platform-tools)

```powershell
.\adb pair IP_RELOJ:PUERTO_EMPAREJAMIENTO     # solo la primera vez; pide el código de 6 dígitos
.\adb connect IP_RELOJ:PUERTO_DEPURACION
.\adb install -r C:\ruta\plc-reset-vN.apk
```
