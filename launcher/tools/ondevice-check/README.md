# On-device check

Runs Axiom's on-device runtime (`LiteRtRuntime`, compiled from the app's sources) against a real Gemma 4 file
on a computer, using LiteRT-LM's desktop build, the way Solis drives it: whole-conversation requests, tools run
by the caller, cancellation, thinking, JSON, images.

```
curl -L -o gemma-4-E2B-it.litertlm \
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1caa527c1e88b55f046fcd8d2b4265606680663d/gemma-4-E2B-it.litertlm
cd launcher
./gradlew -p tools/ondevice-check run --args="$PWD/../gemma-4-E2B-it.litertlm $PWD/app/src/main/assets/ondevice/gemma4_chat_template.jinja all /tmp/litertlm-cache"
```

The last argument is LiteRT-LM's cache folder (the first run builds it; later loads take under a second).
Sections: `loop`, `think`, `json`, `cancel`, `vision`, `limits`, or `all`. Add `spec` as a fifth argument to run
with speculative decoding on the CPU as well (the app turns it on for the GPU only).
