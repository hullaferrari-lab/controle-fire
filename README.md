# Controle Fire

Controle remoto Android de código aberto para Fire TV Stick, em Kotlin e Jetpack Compose. Interface em português, navegação por Wi-Fi, Alexa e quatro atalhos editáveis.

[Baixar APK](https://github.com/hullaferrari-lab/controle-fire/releases/latest) · [Compilar](docs/COMPILAR.md) · [Contribuir](CONTRIBUTING.md) · [Doações](DONATE.md)

## Referência do projeto

O [Amazon Fire TV oficial](https://play.google.com/store/apps/details?id=com.amazon.storm.lightning.client.aosp) é a referência de comportamento e compatibilidade para o desenvolvimento. A interface é inspirada na disposição do controle físico. A implementação deste projeto é própria; não inclui o APK oficial, seu código decompilado, bibliotecas privadas ou recursos gráficos da Amazon. O projeto é independente e não é afiliado à Amazon ou à Samsung. Veja [referências e método](docs/REFERENCIAS.md).

## O que funciona e o que precisa de validação

| Recurso | Situação na versão 0.3.2 |
| --- | --- |
| Setas, OK, voltar, início e menu | Wi-Fi nativo pareado, com ADB como alternativa |
| Reproduzir/pausar | Wi-Fi nativo; outros comandos de mídia usam compatibilidade ADB |
| Quatro atalhos | Toque abre; toque longo seleciona app instalado, incluindo sideload |
| Alexa | Microfone do celular por Wi-Fi; requer pareamento e permissão |
| Bluetooth HID | Implementado para Android 9+; pareamento físico Note20/Fire Stick ainda não confirmado |
| Volume, mudo e energia da TV | Sem suporte confirmado; botões exibem a limitação |
| Android 8+ | Wi-Fi; Bluetooth exige Android 9+ e suporte HID do aparelho |

O alvo inicialmente testado é Fire TV Stick de 3ª geração com Fire OS 7.7.1.7. Não há garantia de compatibilidade com todo modelo ou firmware. A versão atual ainda depende de ADB na conexão inicial e na abertura dos atalhos. Alexa e atalhos precisam de Wi-Fi mesmo com navegação Bluetooth.

## Baixar e instalar

1. Abra [Releases](https://github.com/hullaferrari-lab/controle-fire/releases/latest) e baixe `Controle-Fire-0.3.2-debug.apk` em **Assets**.
2. No Android, permita temporariamente instalar apps pela fonte usada para abrir o arquivo e instale o APK.
3. Coloque celular e Fire Stick na mesma rede Wi-Fi.
4. No Fire Stick, ative **Depuração ADB** em **Meu Fire TV → Opções do desenvolvedor**. Se o menu estiver oculto, entre em **Sobre** e pressione sete vezes o nome do dispositivo.
5. Consulte **Meu Fire TV → Sobre → Rede**, informe o IP no app e autorize a depuração na TV.
6. Na engrenagem do app, escolha **Parear Wi-Fi rápido e Alexa** e digite o código mostrado na TV. A tela deve indicar **Wi-Fi nativo**.

A release inicial é uma compilação **debug para testes**, assinada, com checksum SHA-256. Não desinstale a versão anterior se quiser preservar os pareamentos e atalhos e a nova versão tiver o mesmo certificado. Compilações próprias e de CI podem usar outra chave e não atualizar o APK distribuído; veja [assinatura](docs/COMPILAR.md#assinatura-e-atualizações).

## Usar

- Segure o microfone azul, espere **Ouvindo**, fale e solte. A captura para ao perder foco ou sair do app, com limite de 15 segundos.
- Toque longo em um dos quatro atalhos para escolher ou remover o app. A seleção é salva por Fire Stick.
- Para Bluetooth: engrenagem → **Configurar Bluetooth** → ativar controle → permitir dispositivos próximos → tornar celular visível. No Fire Stick, abra **Controles e dispositivos Bluetooth → Outros dispositivos Bluetooth → Adicionar** e escolha o celular. Só há conexão após confirmação do Android. Mantenha o app aberto.
- Se houver problema de rede, volte ao modo ADB ou reconecte. Volume/mudo da TV não serão habilitados simplesmente ao parear Bluetooth.

## Compilar

Requisitos: JDK 17, Android SDK Platform 35 e Build Tools 35.0.0.

```bash
git clone https://github.com/hullaferrari-lab/controle-fire.git
cd controle-fire
# Configure ANDROID_HOME ou sdk.dir em local.properties
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. O Gradle Wrapper já está incluído. O guia [COMPILAR](docs/COMPILAR.md) inclui Android Studio, Windows, testes e assinatura.

O workflow **Android CI** compila, testa, executa lint e disponibiliza um APK de teste em **Actions → execução → Artifacts**. Os APKs publicados em Releases ficam disponíveis sem depender da retenção dos artifacts de CI.

## Privacidade e permissões

Sem anúncios, cadastro no nosso app, analytics ou servidor próprio. ADB, controle nativo e catálogo usam a rede local. O áudio segue para o Fire Stick e os serviços Alexa/Amazon; o app não salva gravações. Identidade ADB, pareamento e atalhos ficam no armazenamento privado, com backup desativado. TLS do receptor é fixado no pareamento; reativação DIAL usa HTTP local. Bluetooth e microfone só são usados nos recursos correspondentes. Veja [PRIVACIDADE](PRIVACY.md).

## Contribuições e próximos passos

Leia [CONTRIBUTING](CONTRIBUTING.md), [validação](docs/VALIDACAO.md) e [roadmap](docs/ROADMAP.md). Mudanças devem comparar o comportamento com o app oficial e distinguir testes reais, testes locais e inferências.

## Apoie o projeto

Doações são opcionais e não desbloqueiam funções.

| Moeda / rede | Endereço |
| --- | --- |
| Bitcoin (BTC, rede Bitcoin) | `bc1q3h9nrr59y7s7jel56svgrkzgrdjztf3kme962s` |
| Ether (ETH, rede Ethereum) | `0x1474293a5B79e4438657e9635B62E56dCACD7793` |
| TRON (TRX, rede TRON) | `TMYS1iggVKGXoQGFNfdqJPMhZy9vqkKZ8c` |

Mais informações em [DONATE.md](DONATE.md).

## Licença

Código próprio sob [MIT](LICENSE). Créditos e licenças de referências de protocolo em [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt). Marcas e aplicativos de terceiros pertencem aos respectivos titulares.
