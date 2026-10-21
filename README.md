# Upscayl iOS (fork, Swift + Core ML)

Réécriture native iOS d'[Upscayl](https://github.com/upscayl/upscayl). Même famille de modèles (Real-ESRGAN), moteur Core ML au lieu de ncnn/Vulkan.

## Utilisation
1. Pousse ce dossier dans un repo GitHub (branche `main`).
2. Onglet **Actions → Build IPA** (se lance au push, ou *Run workflow*).
3. Télécharge l'artefact **Upscayl-ipa** (zip contenant `Upscayl.ipa`, non signé).
4. Installe-le depuis Windows avec Sideloadly ou AltStore (iOS 16+).

Sur Mac : `xcodegen generate` puis ouvrir `Upscayl.xcodeproj` (il faut d'abord lancer `python scripts/convert_models.py` et copier les `.mlmodelc` compilés dans `Models/` du bundle).

## Notes
- Modèles : standard (photos), illustrations/anime, rapide. Échelles ×2/×3/×4 (×2 et ×3 = sortie ×4 réduite).
- Limite mémoire : sortie ×4 plafonnée à 64 Mpx (`Upscaler.maxOutputPixels`) ; au-delà, la source est réduite.
- Garde l'app au premier plan pendant le traitement.
- Licence : AGPL-3.0 (code Upscayl) + Real-ESRGAN (BSD-3), voir `LICENSE` et `Real-ESRGAN_LICENSE.txt`.
