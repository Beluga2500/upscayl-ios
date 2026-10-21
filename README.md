# Upscayl iOS + Android (forks natifs)

Réécritures natives d'[Upscayl](https://github.com/upscayl/upscayl), faites par Akuu. Akuu ne détient aucun droit sur Upscayl, ses modèles ni son logo : tout appartient à leurs créateurs (voir l'onglet « À propos » des apps).

| Plateforme | Dossier | Moteur | Workflow | Artefact |
|---|---|---|---|---|
| iOS 16+ | racine (`Sources/`, `project.yml`) | Real-ESRGAN converti en Core ML | `build-ipa.yml` | `Upscayl-ipa` (.ipa non signé, à sideloader) |
| Android 10+ (arm64) | `android/` | modèles ncnn d'Upscayl (Vulkan, repli CPU) | `build-apk.yml` | `Upscayl-apk` (.apk signé) |

Chaque workflow se lance au push (iOS ignore les changements dans `android/`, et inversement) ou à la main : onglet **Actions → Run workflow**.

```
gh run download -n Upscayl-ipa
gh run download -n Upscayl-apk
```

## Notes
- ×2/×3 = sortie ×4 réduite. Sortie ×4 plafonnée à 64 Mpx.
- Garde l'app au premier plan pendant le traitement.
- Licences : AGPL-3.0 (Upscayl), Real-ESRGAN (BSD-3), ncnn (BSD-3). Voir `LICENSE` et `Real-ESRGAN_LICENSE.txt`.
