# Face Sort

![GitHub commit activity](https://img.shields.io/github/commit-activity/t/psilo-hub/facesort)
![GitHub last commit](https://img.shields.io/github/last-commit/psilo-hub/facesort)
![GitHub Downloads](https://img.shields.io/github/downloads/psilo-hub/facesort/total)
![GitHub Release Date](https://img.shields.io/github/release-date/psilo-hub/facesort)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)
[![Platform: Windows · Linux · macOS](https://img.shields.io/badge/Platform-Windows%20%E2%80%A2%20Linux%20%E2%80%A2%20macOS-informational.svg)]()
[![Download](https://img.shields.io/badge/Download-latest%20release-blue.svg)](https://github.com/psilo-hub/facesort/releases)

> [English](README.md) · [Deutsch](README.de.md) · Français · [Español](README.es.md) · [Русский](README.ru.md) · [中文](README.zh.md)

Une **application de bureau** multiplateforme qui trie votre collection de photos
par personnes. Elle détecte les visages dans vos photos, les regroupe
automatiquement par similitude et vous permet de donner un nom à chaque personne
— puis de réviser, fusionner et nettoyer votre bibliothèque. Tout s'exécute
localement sur votre ordinateur : **vos photos ne quittent jamais votre machine.**

## Fonctionnalités

- **Détection automatique des visages** à l'import de vos photos — chaque visage
  détecté est conservé sous forme de vignette, prêt à être nommé.
- **Regroupement automatique des visages non nommés** — les visages similaires
  sont regroupés en clusters, pour que vous puissiez nommer toutes les photos
  d'une même personne en une fois.
- **Trois façons simples de nommer des visages :**
  - **Associer un visage à un nom** — avancez dans les groupes de visages non
    nommés, les plus grands d'abord.
  - **Nommer un visage au hasard** — parcourez des visages non nommés au hasard
    et nommez ceux que vous sélectionnez.
  - **Ajouter des visages à un nom** — choisissez une personne et nommez les
    visages non nommés qui lui ressemblent le plus.
- **Détection des doublons** — compare vos personnes nommées et vous permet de
  fusionner des noms qui s'avèrent être la même personne.
- **Parcourir et réviser** — voyez chaque personne d'un coup d'œil, explorez les
  photos qui la contiennent, ouvrez les originaux dans votre visionneuse et
  retirez les noms.
- **Import intelligent** — parcourt les sous-dossiers de façon récursive et saute
  les photos déjà importées (par contenu, pas par nom de fichier). Réimporter un
  même dossier est donc sans effet, et une même photo n'est jamais stockée deux
  fois.
- **Filtre de chemin** — limitez le nommage à un dossier ou un nom de fichier
  précis.
- **Renommer et retirer un nom** — corrigez une faute de frappe partout d'un
  coup, ou retirez un visage d'un nom.
- **Exporter les photos d'une personne** — dans *Ajouter des visages à un nom*,
  choisissez une personne et copiez chaque photo la contenant dans un dossier de
  votre choix (les photos dont le fichier d'origine a disparu sont exportées sous
  forme de vignettes à la place).
- **Téléchargement des modèles au premier lancement** — les modèles intégrés de
  reconnaissance faciale sont téléchargés une seule fois (avec une fenêtre de
  progression) et mis en cache localement.
- **Vérification automatique des mises à jour** — vous prévient quand une nouvelle
  version est disponible.
- **Onglet Feedback** — envoyez des rapports de bug et des demandes de
  fonctionnalités directement depuis l'application.
- **Ensuite entièrement hors ligne** — détection et correspondance s'exécutent
  localement sur votre processeur.

## Prérequis

- **Java 17 ou plus récent** (un JDK, pas seulement un JRE)
- Un système de bureau **64 bits** — Windows, Linux (x86_64 / aarch64) ou macOS
  (Intel / Apple Silicon)
- Quelques Go de mémoire vive libre sont recommandés pour les grandes collections
- **FFmpeg** — intégré à l'application (via ffmpeg4j) ; rien à installer

## Téléchargement

Téléchargez la dernière version depuis la page
[**Releases**](https://github.com/psilo-hub/facesort/releases) — choisissez le
jar de votre plateforme (`facesort-<platform>.jar`). Des jars pré-construits pour
toutes les plateformes sont joints à chaque version. Aucune compilation ni
configuration requise.

### Premier lancement

1. Assurez-vous que **Java 17+** est installé (`java -version`).
2. Lancez le jar : `java -jar facesort-<platform>.jar` (ou double-cliquez dessus).
3. Au tout premier lancement, Face Sort télécharge ses modèles de reconnaissance
   faciale et affiche une fenêtre de progression. Une connexion internet est
   nécessaire une fois.

Ensuite, tout fonctionne hors ligne.

## Comment utiliser

La fenêtre principale est un ensemble d'onglets. Suivez-les à peu près dans cet
ordre :

1. **Importer des images** — appuyez sur *Parcourir…* pour choisir un dossier de
   photos (JPG, JPEG, PNG, BMP, GIF, WebP ; les sous-dossiers sont parcourus de
   façon récursive), puis appuyez sur **Importer**. Chaque visage détecté est
   stocké avec son embedding et une vignette. Vous pouvez arrêter un import à
   tout moment ; les fichiers déjà importés sont conservés. Réimporter ensuite le
   même dossier n'enregistre que les nouveaux fichiers.

2. **Associer un visage à un nom** — L'application regroupe tous les visages non
   nommés et affiche le représentant du plus grand cluster. Saisissez un nom (les
   noms existants sont détectés pendant la saisie) et appuyez sur **Nommer** ; les
   visages similaires restants du cluster sont ensuite proposés pour que vous les
   nommiez dans la même session. Passez au cluster suivant avec *Cluster suivant*.

3. **Nommer un visage au hasard** — un échantillon aléatoire de visages non
   nommés. Cliquez sur des visages pour les sélectionner, saisissez un nom et
   appuyez sur **Nommer la sélection**. Utilisez le champ *préfixe de chemin*
   pour limiter l'échantillon à un dossier ou un fichier particulier.

4. **Ajouter des visages à un nom** — Sélectionnez une personne à gauche ;
   l'application classe chaque visage non nommé selon sa similitude avec
   l'embedding moyen de la personne et affiche les meilleurs candidats.
   Sélectionnez-en plusieurs et appuyez sur **Nommer la sélection** — cliquez sur
   les visages un à un, ou maintenez **Maj** enfoncée et cliquez sur le premier
   et le dernier visage pour sélectionner toute la plage entre les deux. Utilisez
   la case *« Exclure les visages plus proches d'un autre nom »* pour ne proposer
   que les visages dont la meilleure correspondance est la personne sélectionnée,
   et le champ *préfixe de chemin* pour filtrer par dossier. Vous pouvez aussi
   **Renommer…** n'importe quelle personne ici, ou appuyer sur
   **Exporter les images…** pour copier chaque photo de la personne sélectionnée
   dans un dossier de votre choix (les originaux manquants sont exportés comme
   vignettes). Un clic droit sur un visage candidat non nommé permet de choisir
   *Étiqueter avec un autre nom* pour l'étiqueter avec un nom autre que celui
   proposé : la boîte de dialogue prévisualise les noms existants avec leur
   visage le plus représentatif et la similarité avec l'embedding moyen du nom.

5. **Supprimer les doublons** — appuyez sur **Démarrer** pour comparer les paires
   de noms par similitude. Pour chaque paire, décidez : *Ce sont des doublons*
   (choisissez alors quel nom survit — tous les visages sont fusionnés),
   *Ce ne sont pas des doublons* (mémorisé définitivement), ou *Ignorer*.

6. **Voir** — parcourez votre collection nommée. Chaque personne est une carte
   avec un visage représentatif et le nombre de visages nommés. Cliquez sur une
   carte pour voir les images contenant cette personne ; cliquez sur une image
   pour ouvrir l'original, ou utilisez le menu contextuel pour *Retirer le nom* de
   cette personne.

7. **Paramètres** — réglez la détection des visages, le regroupement, l'import et
   les modèles ; consultez l'info-bulle de chaque contrôle pour les détails.
   Les modifications sont appliquées avec **Enregistrer** ou réinitialisées avec
   **Rétablir les valeurs par défaut**.

8. **Feedback** — envoyez un rapport de bug ou une demande de fonctionnalité aux
   développeurs.

Dans toute l'application, vous pouvez faire un clic droit sur un visage ou une
vignette pour **Ouvrir l'original** dans la visionneuse par défaut de votre
système ; survoler un visage affiche le chemin de son image source.

### Flux de travail suggéré

```
Importer un dossier  →  Associer un visage à un nom / Nommer un visage au hasard / Ajouter des visages à un nom (nommer les personnes)
                     →  Supprimer les doublons (fusionner les noms en double)
                     →  Voir (réviser, ouvrir les originaux, retirer les erreurs)
```

## Où vos données sont stockées

Tout vit dans un dossier `config/` à côté de l'application (créé au premier
lancement) — copiez-le pour sauvegarder votre bibliothèque :

| Chemin | Rôle |
|--------|------|
| `config/facesort.db` | Votre bibliothèque : photos, visages, noms, tags |
| `config/facesort-config.json` | Vos paramètres |
| `config/CHANGELOG.md` | Cache utilisé par la vérification des mises à jour |
| Cache des modèles FaceAI | Modèles téléchargés — par défaut `~/.djl.ai/cache` (Linux/macOS) ou `%USERPROFILE%\.djl.ai\cache` (Windows) ; configurable via le paramètre *FaceAI cache dir* |

Tous les paramètres peuvent être modifiés dans l'onglet **Paramètres** (chaque
contrôle possède une info-bulle explicative) ; ils sont stockés dans
`config/facesort-config.json`. Vous pouvez aussi y changer la **langue** de
l'interface.

## Licence

Distribuée sous la [Licence MIT](LICENSE).