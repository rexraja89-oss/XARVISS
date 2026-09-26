# XARVIS Build Status

**Last updated:** 26 September 2026
**Status:** Working, installed and tested on a Galaxy S22 Ultra and a benco V91s Plus (Android 13)

## Working

- Commands: open apps, web search, maps navigation, device status, time, help
- Persistent memory, shared between linked devices ("forget everything" clears it everywhere)
- On-device AI: Gemma 4 E2B via LiteRT-LM, ~15 tokens/s on the S22's CPU; understands free-form requests and can trigger the commands itself
- Device linking over Wi-Fi: automatic discovery, 6-digit pairing code, encrypted requests, remote status, notes, and using a linked device's AI model

## Known limitations

- The S22's GPU path produces corrupted text (old Adreno driver), so XARVIS calibrates once and uses the CPU there
- Linking is local-network only
- Each phone needs the model file copied separately (see README); the benco uses the S22's model over Wi-Fi instead

## Next

1. Remote access outside home Wi-Fi (Tailscale)
2. Windows laptop companion
