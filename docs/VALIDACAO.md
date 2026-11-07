# Validação e limites

Para 0.3.2: build debug, testes unitários e lint passaram localmente. Foram 34 testes: 31 passaram, 3 testes opcionais de hardware foram ignorados. Os testes cobrem protocolo/identidade ADB, validação de comandos, pareamento nativo simulado, TLS, áudio WebSocket, liberação de teclas, estado HID, expiração da fila e encerramento de voz.

Testes históricos no Fire TV Stick de 3ª geração / Fire OS 7.7.1.7 confirmaram navegação, consulta/abertura de apps, reconexão e uma solicitação de voz. Não se repetiram todos os testes físicos a cada patch. A rota Wi-Fi nativa apresentou menor tempo de ida/volta que ADB na bancada; isso não garante a mesma latência visual em outro celular/rede.

Bluetooth HID foi exercitado em emulador Android 15, incluindo registro de perfil e permissões. Isso não confirma pareamento por rádio com Note20 Ultra ou todos os receptores Fire TV.

Na Samsung PN43H4000AG, volume/mudo por ADB interno e pedidos nativos não alteraram o som físico. O app não anuncia esses botões como funcionais. HDMI-CEC, volume, energia e TV ao vivo precisam de investigação e validação por hardware.

0.3.2 move abertura de atalhos para a conexão secundária de catálogo e descarta resultados de gerações antigas. A suíte existente passou, mas não há teste dedicado dessa corrida nem medição física nova do ganho. Falhas ao abrir apps não devem derrubar o transporte principal.

Ao reportar resultados, diferencie compilação, teste automatizado, execução em emulador e observação no aparelho real. Não envie dados de conta ou de rede.
