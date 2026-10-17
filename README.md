# RetroDroid

Emulador Android (Kotlin + LibretroDroid) para SNES, Mega Drive, NES e Nintendo 64.

## Gerar o APK pelo celular
1. Suba TODOS os arquivos desta pasta (inclusive `.github/`) para um repositório no GitHub.
2. **Actions → Build APK → Run workflow** (ou dê push).
3. Quando ficar verde, baixe o artefato **RetroDroid-apk**, descompacte e instale o `.apk`.

O workflow descobre sozinho uma versão da LibretroDroid que existe no JitPack (veja o passo
"Descobrir versão..." no log) e baixa os cores: Snes9x, Genesis Plus GX, FCEUmm e Mupen64Plus-Next.

## Formatos
.sfc .smc (SNES) • .gen .md .smd .bin (Mega Drive) • .nes (NES) • .z64 .n64 .v64 (Nintendo 64)

## Novidades
- Gravação de vídeo com áudio (pede permissão de microfone e de "captura de tela" só pra pegar o som do jogo).
- Mini-controle do console no canto do vídeo (botão apertado = branco).
- Launcher com fundo animado, capas/nomes personalizados.
- Se o app fechar de repente, o erro aparece na próxima abertura (botão Copiar).
- Configurações Avançadas → "Diagnóstico dos botões" mostra qual tecla cada botão da tela está enviando.
