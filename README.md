# BatteryLens

Monitor de bateria para Android (Kotlin + Jetpack Compose), com todos os recursos liberados e sem anúncios.

## Recursos

| Área | O que faz |
|---|---|
| **Carga** | Corrente (mA) e potência (W) em tempo real, fonte (AC/USB/sem fio), tempo até a meta e até 100% (com modelo da fase CV acima de 80%) |
| **Diagnóstico de velocidade** | Classifica a carga (lenta/normal/rápida/super-rápida) e explica o que está limitando: fase final, calor, tela ligada, porta USB, sem fio ou carregador/cabo fraco |
| **Alarme de carga** | Avisa ao atingir a meta (50–100%), com som de alarme insistente opcional; silencia ao desconectar |
| **Desgaste** | Desgaste por sessão em "ciclos equivalentes", ponderado pelo nível da carga; comparação "parar em X% vs 100%" |
| **Saúde** | Capacidade estimada em cada carga ≥ 15% (média ponderada), comparada à capacidade projetada → saúde em % e gráfico ao longo do tempo |
| **Descarga** | %/h e mA médios com tela ligada e desligada, tempo de tela, sono profundo (%), tempo restante (tela ligada, tela desligada, combinado), média dos últimos 7 dias |
| **Consumo por app** | Energia atribuída ao app em primeiro plano (exige "Acesso aos dados de uso") |
| **Histórico** | Todas as sessões de carga/descarga com gráficos de nível, corrente e temperatura; filtro, exclusão e exportação CSV |
| **Alarmes extras** | Temperatura alta e bateria baixa |
| **Notificação** | Status contínuo com corrente, estimativas, temperatura, tensão e dados da sessão |
| **Temas** | Sistema, claro, escuro e preto AMOLED (Material You no Android 12+) |

## Como funciona

- `BatteryMonitorService` (serviço em primeiro plano) amostra a bateria a cada N segundos e a cada evento de tela/tomada/nível. Ele **não** segura wake lock: durante o sono profundo o contador de carga do fuel gauge cobre o intervalo.
- `SessionTracker` transforma amostras em sessões persistidas no Room e retoma a sessão aberta após um reinício do processo.
- Toda a matemática fica em `domain/BatteryMath.kt` e `domain/ChargeDiagnostics.kt`, código Kotlin puro coberto por testes unitários.

## Limitações honestas

- **Nenhum app consegue controlar a tensão/corrente do carregador** sem root. Isso é negociado entre o chip de carga e o carregador (USB PD/PPS). O app mede e diagnostica.
- A unidade e o sinal da corrente variam entre fabricantes. Se os valores parecerem errados, ajuste "Unidade da corrente" nas Configurações.
- A capacidade projetada vem de uma API interna do Android. Se não aparecer, informe-a manualmente.
- O modelo de desgaste é uma aproximação original (estresse exponencial por nível), não uma medição química.

## Build

Requer JDK 17 e Android SDK 34.

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Para instalar com o celular conectado (depuração USB ativa):

```powershell
D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

Depois de abrir: permita notificações, conceda "Acesso aos dados de uso" (para o consumo por app) e deixe o app sem restrição de bateria.
