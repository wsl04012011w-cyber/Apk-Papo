# Papo Android

Aplicativo Android nativo com uma tela WebView que abre o Papo publicado em https://hjhjh-ruby.vercel.app/.

## Objetivo desta primeira etapa

- Manter o projeto Android separado do repositório web `Hjhjh`.
- Criar uma base Android mínima e segura para testes.
- Preparar permissão de microfone para as chamadas de voz já implementadas no site.
- Gerar automaticamente um APK de depuração pelo GitHub Actions.

## Como gerar o APK

1. Abra a aba **Actions** deste repositório.
2. Selecione **Build Android APK** e, quando necessário, execute **Run workflow**.
3. Abra a execução concluída e baixe o artefato **papo-debug-apk**.
4. Extraia o ZIP e instale `app-debug.apk` em um dispositivo Android de teste.

Cada push na branch `main` também inicia uma compilação.

## Desenvolvimento local

Requisitos: JDK 17, Android SDK Platform 35 e Gradle 8.9.

```bash
gradle assembleDebug
```

APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

## Limites conhecidos desta etapa

- O app carrega o site publicado; mudanças no frontend continuam sendo publicadas pelo fluxo web/Vercel já existente.
- O login Google baseado em `signInWithPopup` pode não funcionar corretamente dentro de WebView. A integração de autenticação precisa ser tratada numa etapa própria; não se deve contornar a proteção do Google OAuth em WebView incorporada.
- Notificações de chamadas com o app fechado, FCM, serviços em segundo plano e integração nativa de chamadas ainda não foram implementados.
- Este é um APK de depuração para testes, não uma versão de produção assinada.

## Segurança

O WebView permite JavaScript porque o frontend precisa dele, mas mantém acesso a arquivos locais desativado, bloqueia conteúdo misto e concede captura de áudio somente à origem HTTPS do Papo. Não coloque chaves privadas, keystores ou senhas neste repositório.
