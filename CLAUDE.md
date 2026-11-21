# onzgram — правила для ассистента

Форк Android-клиента Telegram на базе exteraless. Пакет: `com.oniczzz.onzgram`.

## Сборка

- **Локально Gradle НЕ запускать** (`./gradlew`, сборка из IDE и т. п.). Сборка идёт только в GitHub Actions.
- Пуш в `main` → `.github/workflows/release.yml` собирает `assembleRelease`, подписывает debug-ключом
  (`RELEASE_KEYSTORE` не нужен), кладёт APK в Artifacts (`onzgram-release`) и в pre-release `ci-<sha>`.
- Секреты: `LOCAL_PROPERTIES` (base64 от `local.properties` → декодируется в `local.properties`),
  необязательные `DEBUG_KEYSTORE` (base64 debug.keystore для стабильной подписи), `TG_*` (отправка в Telegram).
- Раз собрать локально нельзя, перед коммитом вручную проверять: импорты, синтаксис Java/Kotlin,
  XML-ресурсы (нет дублей `name`, экранированы `'`), проверки `Build.VERSION.SDK_INT` перед API выше minSdk 24
  (RenderEffect — 31, RuntimeShader — 33, `clipOutPath` — 26, `Canvas.drawRenderNode` — 29).
- `local.properties` в `.gitignore` — не коммитить.

## Ребрендинг

- `APP_PACKAGE` в `gradle.properties`; в `TMessagesProj/google-services.json` должен быть клиент с этим пакетом.
- Пакет зашит в `res/xml/{auth,auth_menu,contacts,shortcuts,sync_contacts}.xml` и mime-типы в `AndroidManifest.xml`;
  Java берёт его из `BuildConfig.APPLICATION_ID`.
- Имя приложения: `AppName` в `res/values*/strings.xml`, `NagramX` в `res/values/strings_nax.xml`.
- Апдейтер: `app/exteraless/updater/GitHubUpdater.java` (`REPO = "oniczzz/onzgram"`).

## Liquid Glass

- Контроллер: `TMessagesProj/src/main/java/app/exteraless/appearance/glass/LiquidGlassController.java`
  (уровни: API 33+ AGSL + RenderEffect blur, 31–32 blur + градиентная обводка 1dp, ниже — полупрозрачный фон).
- Фон попапов: `.../appearance/glass/LiquidGlassPopupBackground.java` (PixelCopy → RenderNode + RenderEffect).
- Конфиг: `TMessagesProj/src/main/kotlin/app/exteraless/appearance/AppearanceConfig.kt`
  (`liquidGlass`, `liquidGlassIntensity` 0..100).
- Экран настроек: `app/exteraless/settings/OpenExteraAppearanceActivity.java` (секция «Размытие»).
- Точки интеграции: `ActionBar.setupGlass/createMenuGlass/dispatchDraw`, `ChatActivityEnterView.drawBackground`,
  `Components/chat/ChatInputViewsContainer.setInputIslandBubbleDrawable`, `ActionBarPopupWindow.drawLiquidGlassBackground`,
  `MainTabsActivity` (нижняя навигация), `DialogsActivity` (панель папок).
  Стеклянные поверхности Telegram (`blur3/drawable/BlurredBackgroundDrawable`) получают слой через `setLiquidGlassOverlay(true)`.
- Цвета — из `Theme` (`key_windowBackgroundWhite`, `key_windowBackgroundWhiteBlueHeader`) на каждой отрисовке.

## Прочие конфиги

- Призрак: `tw/nekomimi/nekogram/NekoConfig.java` + `com/radolyn/ayugram/utils/AyuGhostUtils.java`
  (перехват в `ConnectionsManager.sendRequestInternal`), экран `tw/nekomimi/nekogram/settings/GhostModeActivity.java`.
- Удалённые сообщения: `xyz/nextalone/nagram/NaConfig.kt` (`enableSaveDeletedMessages`),
  экран `app/exteraless/settings/OpenExteraAyuMomentsActivity.java`, логика в `com/radolyn/ayugram/messages`.
