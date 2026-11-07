# Diretrizes do Controle Fire

- Use o app Amazon Fire TV oficial como referência de comportamento e compatibilidade; preserve uma implementação própria. Consulte docs/REFERENCIAS.md.
- Não adicione APKs de terceiros, código decompilado, marcas/ativos proprietários, credenciais, gravações, dados de contas ou de dispositivos ao repositório.
- Não prometa volume, energia, Bluetooth físico ou menor latência sem evidência real no hardware. Separe fatos observados, inferências e limites.
- Mantenha Kotlin/Compose, português e Android 8+ no Wi-Fi; Bluetooth HID exige Android 9+.
- Valide mudanças relevantes com `bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` e documente testes de hardware não executados.
- Nunca versione chaves de assinatura; APKs públicos e checksums pertencem às Releases.
