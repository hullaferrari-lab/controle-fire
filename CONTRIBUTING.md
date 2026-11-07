# Como contribuir

Abra uma issue descrevendo o problema ou envie um pull request com uma alteração focada. Para falhas, informe versão do app, Android, modelo do Fire TV, Fire OS e transporte selecionado. Não publique tokens, chaves, PINs, dados de conta, gravações ou endereços da sua rede.

Use o **Amazon Fire TV oficial** como referência de comportamento para conexão, navegação, pareamento e Alexa. Informe qual versão foi comparada, o que foi observado e o que continua desconhecido. Não copie nem envie APKs oficiais, fontes decompilados, imagens/logos ou componentes proprietários. Nossa implementação deve continuar independente. Referências abertas devem conservar suas licenças.

Antes de enviar:

```bash
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Descreva o problema, o comportamento resultante, a validação e seus limites. Testes sem aparelho não comprovam compatibilidade Bluetooth, volume físico nem latência visual. Nunca use HTTP 200 sozinho como prova de que uma função de hardware funciona. Não repita automaticamente comandos não idempotentes depois de falha de rede.

O código usa Kotlin e Compose com interface em português. Preserve Android 8 para Wi-Fi e as verificações de API/permissão do Bluetooth. Novas dependências devem ter licença compatível e motivo concreto.
