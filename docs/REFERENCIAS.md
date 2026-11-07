# Referência oficial e implementação independente

A referência principal é o [Amazon Fire TV oficial](https://play.google.com/store/apps/details?id=com.amazon.storm.lightning.client.aosp), pacote `com.amazon.storm.lightning.client.aosp`. O desenho segue a disposição de botões do controle físico, com quatro atalhos editáveis.

Na investigação de 07/11/2026, um APK 4.9.1 fornecido por um aparelho autorizado foi analisado estaticamente com REA 6.8.0 e JADX headless 0.7.1. A comparação confirmou no código selecionado o uso de AudioRecord com VOICE_RECOGNITION, áudio mono PCM 16 bits/16 kHz e WebSocket seguro na porta 9090. Também foi identificado controle de geração e cancelamento de pedidos de rede. Essas evidências estáticas não demonstram execução completa nem equivalência de todos os comportamentos.

Parte da interface/lógica está em bytecode Hermes; o fluxo de volume e o papel exato do Bluetooth no app oficial não foram confirmados. Permissões, nomes de funções e resposta HTTP 200 não comprovam suporte de hardware.

Este repositório não redistribui o APK oficial, bytecode, material decompilado ou recursos proprietários. A implementação é nossa, e a comparação serve para validar decisões de comportamento, não copiar o produto. As referências abertas de pesquisa de protocolo são [LibreControl/fire-tv](https://github.com/LibreControl/fire-tv) e [hms-homelab/hms-firetv](https://github.com/hms-homelab/hms-firetv); os avisos MIT estão em THIRD_PARTY_NOTICES.txt.

Ao mudar uma função, registre versão/modelo comparados, procedimento, resultado, evidência e o que continua desconhecido. Considere que futuras versões oficiais e do Fire OS podem alterar os protocolos.
