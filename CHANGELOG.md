# Changelog

このプロジェクトは [Keep a Changelog](https://keepachangelog.com/) の
形式にゆるく従います。

## [v1.0.0] - 初回リリース

### 追加

- 翻訳コア: 複数API設定・優先度付き自動フェイルオーバー・レート制限/クォータ/
  認証失敗の分類・クールダウン復帰
- 対応プロバイダ: OpenAI互換、DeepL、Google Cloud Translation v2、
  Google Translate Web版(無償・非公式)
- 既存翻訳の優先使用(Minecraft/mod言語ファイル、ユーザー自作言語ファイル)
- アイテム名・ツールチップ・エンティティ名の翻訳
- チャット翻訳(クライアント側既定、サーバー側per-playerモードはオプトイン)
- per-playerサーバー言語設定・ON/OFF
- 永続キャッシュ(ワールドセーブ内、チャットは対象外)
- コマンド `/alltranslator`(`/at`) enable/disable/status/language
- 設定UI(L キー、Mod Menu、NeoForge Configボタン)
- GitHub Actions によるタグベースのリリースパイプライン

### 既知の制限

- 独自Screen/Widgetフレームワーク(libIPN・UI Lib)を使うMod: libIPNは実機確認済み、UI Libは検出のみ実機確認済み(実際の翻訳動作は未確認)。MaliLib・Sodium・Cloth Config・YACL・owo-libは対応不可(公開APIなし)。
- Google Translate Web版(無償・非公式)は外部エンドポイント側の制約により予告なくレート制限がかかる場合がある。
- DeepL・Google Cloud Translation v2は実際のAPIキーでの疎通確認は未実施(モックサーバー検証のみ)。
- ローカルLLM(7Bクラス量子化モデル)は固有名詞・専門用語の翻訳品質にばらつきがある。`config/alltranslator/lang/<code>.json`での手動上書きを推奨。
- LAN参加(非ホスト)クライアントは翻訳結果の永続キャッシュを持たない(メモリキャッシュのみ)。
- サーバー側per-playerチャット翻訳のマルチプレイ実翻訳動作は未確認。
- Anthropic(Claude)・Geminiプロバイダは実際のAPIキーでの動作確認は未実施。

<!-- AT-CHANGELOG-PHASE13-BUGFIX -->
## [Unreleased]

### Fixed
- Traveler's Backpackの「Lantern Upgrade」等、一部アイテムのツールチップ/名前が恒久的に未翻訳のまま固定される不具合を修正。原因は`TranslatableTextInterceptor`の共有キャッシュが、単一APIへの同時アクセス制御(single-flight probe)で敗れたリクエストの「翻訳失敗(原文)」結果を永久にキャッシュしてしまっていたこと。修正後は該当ケースのみキャッシュから除外し、次回呼び出し時に再翻訳を試みるようにした。
- クライアントのMinecraft表示言語と翻訳対象言語(`forcedTargetLanguage`)が同一の場合、他Mod製のUIウィジェット(libIPN/UI Lib経由)から取得した既に対象言語であるテキストが誤って翻訳APIに再送信され、微妙に異なる(誤った)テキストに書き換えられてしまう不具合を修正。`LanguageResolver#resolveLiveClientLanguage()`を追加し、キー無しテキストについてクライアントの生の表示言語と対象言語が一致する場合は翻訳をスキップするようにした。
- 翻訳API(特にローカルLLMサーバー)が高負荷時に一時的な設定エラー相当のHTTPステータス(400/404)を返した場合、即座に該当APIを恒久無効化(`DISABLED_PERMANENT`)してしまい、ユーザーの手動再設定なしには復帰できなくなる不具合を修正。3回連続で発生するまでは短時間クールダウン付きの一時障害として再試行するようにした(`AUTH_FAILED`の即時恒久無効化は変更なし)。

### Known Issues
- UI Lib製の設定画面は検出のみ実機確認済みで、実際の翻訳動作(テキストの書き換わり)は未確認。
- サーバー側per-playerチャット翻訳のマルチプレイ実翻訳動作は未確認。
