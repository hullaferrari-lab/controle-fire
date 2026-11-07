# Compilar o Controle Fire

## Android Studio

1. Instale Android Studio de fonte oficial e abra a pasta do projeto.
2. Selecione **JDK 17** para o Gradle em Settings → Build Tools → Gradle.
3. No SDK Manager, instale **Android SDK Platform 35**, **Build Tools 35.0.0** e Platform Tools.
4. Sincronize o Gradle. O projeto usa AGP 8.9.1, Kotlin 2.1.20 e Gradle Wrapper 8.11.1; não precisa instalar Gradle globalmente.
5. Use Build APK(s) ou execute os comandos abaixo no terminal.

## Linha de comando

```bash
git clone https://github.com/hullaferrari-lab/controle-fire.git
cd controle-fire
export JAVA_HOME=/caminho/do/jdk-17
export ANDROID_HOME=/caminho/do/android-sdk
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Você também pode criar um `local.properties` não versionado:

```properties
sdk.dir=/caminho/do/android-sdk
```

No Windows, configure JAVA_HOME/ANDROID_HOME e use `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Para configurar o SDK por linha de comando, instale o Android Command-line Tools oficial e use `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`; aceite as licenças necessárias no seu ambiente.

APK instalável: `app/build/outputs/apk/debug/app-debug.apk`.
Relatórios: `app/build/reports/tests/testDebugUnitTest/` e `app/build/reports/lint-results-debug.html`.

## Instalar para desenvolvimento

Com seu celular conectado e autorizado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Não é preciso computador durante o uso do controle. No Fire TV, autorize separadamente a chave ADB gerada pelo app no celular.

## Assinatura e atualizações

A primeira distribuição 0.3.2 é **debug**, assinada para testes. Builds debug locais usam a chave do seu ambiente; builds de CI podem gerar uma chave diferente a cada execução. Mesmo applicationId com certificado diferente não permite instalar por cima. Desinstalar remove pareamentos e atalhos.

Para distribuir uma variante própria, prefira applicationId próprio e chave própria. Para release de produção, use Android Studio → Build → Generate Signed Bundle / APK → APK e selecione **release**. O build de release por linha de comando gera APK sem assinatura enquanto não houver signingConfig; assine antes de distribuir. Guarde a chave e senhas fora do repositório e faça backup privado. Nunca publique a chave ou use a chave de debug como chave de produção.

O workflow Android CI não precisa de segredos nem testa hardware: compila debug, testa e executa lint. Seus artifacts têm retenção limitada; as Releases são o ponto público de download das versões distribuídas.

## Testes reais opcionais

A suíte ignora testes físicos quando faltam variáveis. Em ambiente privado, os testes existentes aceitam FIRETV_TEST_HOST, FIRETV_TEST_IDENTITY (pasta com private.der/public.der), FIRETV_ALEXA_CREDENTIALS (JSON de token/certificado) e FIRETV_VOICE_PCM (PCM mono 16 kHz/16 bits). Eles podem enviar comandos ao Fire TV. Não envie esses valores, fixtures privadas ou áudio à CI pública.
