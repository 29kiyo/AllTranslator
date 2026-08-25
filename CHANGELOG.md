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

(Phase 13のテスト結果に基づき、この節を最終確定します)

<!-- AT-CHANGELOG-PHASE13-BUGFIX -->
## [Unreleased]

### Fixed
- Traveler's Backpackの「Lantern Upgrade」等、一部アイテムのツールチップ/名前が恒久的に未翻訳のまま固定される不具合を修正。原因は`TranslatableTextInterceptor`の共有キャッシュが、単一APIへの同時アクセス制御(single-flight probe)で敗れたリクエストの「翻訳失敗(原文)」結果を永久にキャッシュしてしまっていたこと。修正後は該当ケースのみキャッシュから除外し、次回呼び出し時に再翻訳を試みるようにした。

### Known Issues
- libIPNベースの独自Renderable実装を使う設定画面は引き続き翻訳対象外(対応検討中)。
