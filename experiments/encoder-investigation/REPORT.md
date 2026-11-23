# Investigação do encoder AirPlay — Galaxy M62

## Dispositivo testado

- Modelo: `SM-M625F` (`m62xx`, `exynos9825`)
- Android: 13 (SDK 33), build `TP1A.220624.014`
- Servidor de diagnóstico: Tasker Java Code em `127.0.0.1:8181`

## Origem da mensagem `AirPlay encoder failed: null`

A mensagem era produzida em `AirPlayEncoder.start()`:

```java
} catch (Exception e) {
  Log.e(TAG, "start failed", e);
  State.log("AirPlay encoder failed: " + e.getMessage());
  ...
}
```

A exceção capturada era um `java.lang.IllegalArgumentException` com `getMessage() == null`,
lançada por `MediaCodec.configure()`. Por isso o log do Tasker mostrava apenas `null`.

## Código original responsável

Em `AirPlayEncoder.start()`:

```java
MediaFormat format =
    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight);
boolean airplay1 = Pref.getAirPlay1Mode();
format.setInteger(MediaFormat.KEY_BIT_RATE, airplay1 ? 4_000_000 : 8_000_000);
format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, airplay1 ? 1 : 3);
format.setInteger(
    MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
  format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
}
format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
if (airplay1) {
  format.setInteger(
      MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
  format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
}

codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
Surface inputSurface = codec.createInputSurface();
codec.start();
```

## Resultados dos testes no M62

### Teste A — inventário

Arquivo: `results/01_inventory.pretty.json`

Encoders AVC relevantes:

| Encoder | Tipo | Surface input | Largura | Altura | FPS | Bitrate | Alinhamento |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `OMX.Exynos.AVC.Encoder` | hardware | sim | 32..8192 | 32..8192 | 0..960 | 1..120000000 | 2x2 |
| `c2.android.avc.encoder` | software | sim | 16..2048 | 16..2048 | 0..960 | 1..12000000 | 2x2 |
| `OMX.google.h264.encoder` | software | sim | 16..2048 | 16..2048 | 0..960 | 1..12000000 | 2x2 |

Perfis AVC anunciados:

- Exynos: `1`, `2`, `8`, `65536`, `524288` no nível `65536` (Level 4.0).
- Software: `1`, `65536`, `2` no nível `16384`.

HEVC também existe no M62 (`OMX.Exynos.HEVC.Encoder`, hardware), mas o pipeline AirPlay
atual usa AVC. O encoder HEVC software só cobre até 512x512.

`MediaCodec.createEncoderByType("video/avc")` retorna `OMX.Exynos.AVC.Encoder` neste dispositivo
(`results/02_default_codec.txt`).

> Observação: as chamadas `VideoCapabilities.areSizeSupported()` e
> `getSupportedFrameRatesFor()` fizeram a ponte HTTP do Tasker retornar 503 no M62.
> O inventário foi construído apenas com getters estáveis (`getSupportedWidths()`,
> `getSupportedHeights()`, `getSupportedFrameRates()`, `getBitrateRange()`,
> `getWidthAlignment()`, `getHeightAlignment()`).

### Testes B/C/D — inicialização individual

Scripts: `tasker/03_codec_variants*.bsh`  
Resultados: `results/03_*.pretty.json`

Parâmetros reproduzidos do fork:

- MIME: `video/avc`
- Resoluções: `1280x720@30` e `1920x1080@30`
- Bitrate: `8000000` (modo AirPlay 2)
- I-frame interval: `3`
- Color format: `2130708361` (`COLOR_FormatSurface`)
- `max-bframes=0`
- `low-latency=1` no AirPlay 2
- AirPlay 1 adiciona `profile=AVCProfileBaseline`, `level=AVCLevel4`, bitrate `4000000`, I-frame `1`

Variantes testadas:

| Variante | Configuração | Exynos AVC | Software AVC |
| --- | --- | --- | --- |
| 0 | atual AirPlay 2 (`low-latency` + `max-bframes`) | **falha em `configure`**: `IllegalArgumentException`, mensagem `null` | ok |
| 1 | atual AirPlay 1 (baseline L4 + `low-latency` + `max-bframes`) | **falha em `configure`**: `IllegalArgumentException`, mensagem `null` | ok |
| 2 | base sem `low-latency` e sem `max-bframes` | ok | ok |
| 3 | base + `max-bframes=0` | ok | ok |
| 4 | base + `low-latency=1` | **falha em `configure`** | ok |
| 5 | base + baseline/Level 4.0 | ok | ok |

O padrão foi idêntico em 720p30 e 1080p30.

## Causa identificada

O M62 recebe `MediaFormat.KEY_LOW_LATENCY = 1` porque roda Android 13 (SDK >= R).
O encoder AVC da Samsung/Exynos aceito como padrão rejeita essa chave durante
`configure()`, lançando `IllegalArgumentException` com mensagem nula.

Isso explica a diferença entre os dispositivos:

- J7 Pro: Android mais antigo; a condição `SDK_INT >= R` era falsa, então a chave
  `low-latency` nunca era enviada.
- M62: Android 13; a chave era enviada e o `configure()` falhava antes de criar a
  `Surface` ou iniciar o codec.

A resolução negociada (1280x720), o `max-bframes=0`, o perfil baseline e o formato de
entrada Surface não são a causa. Cada um desses parâmetros foi testado isoladamente.

## Estratégia de fallback implementada

Arquivo: `app/src/main/java/io/github/jqssun/displaymirror/airplay/AirPlayEncoder.java`

1. O encoder padrão (`createEncoderByType`) continua sendo a primeira escolha.
2. O formato original continua sendo a primeira tentativa — inclusive `low-latency`,
   quando o SDK permite. Nada muda em dispositivos onde ele funciona.
3. Se `configure()`, `createInputSurface()` ou `start()` falharem, a tentativa é
   registrada com encoder, resolução, FPS, formato, estágio, classe/mensagem da
   exceção e trecho do stack trace.
4. O mesmo encoder é tentado novamente sem `KEY_LOW_LATENCY`.
5. Se ainda falhar, outros encoders AVC com `COLOR_FormatSurface` são tentados,
   hardware primeiro, sem nomes fixos no código.
6. Não há mudança de resolução ou FPS. Não há limites arbitrários de marca/modelo.
7. Cada tentativa usa uma instância nova de `MediaCodec` e libera codec, superfície
   e recursos em caso de falha.
8. O log de sucesso informa o encoder escolhido e se o modo low-latency foi omitido.

A correção anterior que foi revertida não foi reaplicada: esta versão não depende de
`areSizeSupported()`, não altera dimensões e toca apenas `AirPlayEncoder.java`.

## Arquivos modificados no fork

- `app/src/main/java/io/github/jqssun/displaymirror/airplay/AirPlayEncoder.java`
- `experiments/encoder-investigation/` (scripts, resultados e este relatório)

## Seleção manual de encoder e resolução configurada

Implementado a pedido, sem alterar o caminho automático:

- Novo botão **Detect encoders** na tela AirPlay, abaixo de *Support for legacy 3rd party receivers*.
- O botão consulta `MediaCodecList`, filtra encoders AVC com `COLOR_FormatSurface` e
exibe um `Spinner` com nomes e classificação hardware/software. A primeira opção é
**Automatic**, que preserva o fallback anterior.
- A seleção é salva em `Pref.KEY_AIRPLAY_ENCODER`. Quando um encoder específico é
selecionado, a cadeia automática de fallback é desligada; apenas aquele encoder é
tentado (com o retry sem `low-latency`, que corrige o M62).
- Com encoder manual selecionado, `AirPlayService._startEncoder()` usa largura, altura
e FPS definidos em **Change AirPlay Output Settings**, em vez da resolução negociada
pelo receptor. No modo automático, continua usando a resolução negociada.

## Validação

Validado no M62 via Tasker:

- Falha original reproduzida: `configure()` com `low-latency=1` → `IllegalArgumentException: null`.
- Configuração sem `low-latency` no mesmo encoder Exynos: `configure`, `createInputSurface`
  e `start` bem-sucedidos em 720p30 e 1080p30.
- Encoders software também inicializam com todas as variantes testadas.

Ainda pendente:

- CI gerar o APK com estas alterações.
- Teste real de espelhamento AirPlay no M62, incluindo encoder selecionado manualmente.
- Confirmar se o receptor aceita 1920x1080@30 quando a resolução é forçada pela opção manual.
- Teste real de espelhamento no J7 Pro para confirmar que o caminho automático permanece intacto.

Não afirmo que o problema de espelhamento está resolvido até esses testes manuais.
