# All Translator

Minecraft (NeoForge / Fabric, MC 26.2) 用の翻訳Mod。
アイテム名・ツールチップ・エンティティ名・チャットなど、ゲーム内のテキストを
ユーザーが設定した外部翻訳APIを使って翻訳します。

Mod自体は翻訳APIを提供しません。利用するAPI(OpenAI互換エンドポイント、DeepL、
Google Cloud Translation v2、または無償のGoogle Translate Web版)はご自身で
設定画面から登録してください。

## 主な機能

- **既存翻訳の優先使用**: 対象言語のMinecraft/mod言語ファイル、または
  `config/alltranslator/lang/<言語コード>.json` に有効な翻訳が既にある場合、
  APIを一切呼ばずそのまま使用します。
- **翻訳対象**: アイテム名、ツールチップ、エンティティ名、チャット
  (詳細はKnown Limitations参照)。
- **複数API設定 + 自動フェイルオーバー**: 優先度付きで複数のAPIを登録でき、
  レート制限・クォータ超過・認証失敗などに応じて自動的に次のAPIへ切り替えます。
- **対応プロバイダ**: OpenAI互換(Chat Completions形式)、DeepL、
  Google Cloud Translation v2、Google Translate Web版(無償・非公式)。
- **チャット翻訳**: クライアント側翻訳(既定)。サーバー管理者はオプトインで
  サーバー側per-player翻訳モードを有効化可能。
- **per-player言語設定**: サーバー上でプレイヤーごとに異なる言語で表示可能。
- **永続キャッシュ**: 翻訳結果(チャットを除く)をワールドセーブ内に保存し、
  API呼び出しを最小限に抑えます。
- **コマンド**: `/alltranslator`(`/at`) `enable|disable|status|language <code>`
- **設定UI**: `L`キー(または割り当てたキー)、Mod Menu(Fabric)、
  NeoForgeの「Mods」→「Config」ボタンから同一の設定画面を開けます。

## インストール

1. NeoForge または Fabric(対応バージョンのMod Loader)を導入
2. `alltranslator-fabric-<version>.jar` または `alltranslator-neoforge-<version>.jar`
   を `mods` フォルダに配置
3. ゲームを起動後、`L`キー(設定画面から変更可能)で設定を開き、
   翻訳APIを1つ以上登録してください

## 設定ファイル

| ファイル | 内容 |
|---|---|
| `config/alltranslator/config.json` | 一般設定・API設定(キー本体は含まない) |
| `config/alltranslator/credentials.json` | APIキー本体(Git管理・共有しないこと) |
| `config/alltranslator/lang/<code>.json` | ユーザー自作の翻訳ファイル(既存翻訳として最優先) |
| `config/alltranslator/player-settings.json` | サーバー側per-player翻訳設定(サーバーローカル) |
| `<world>/alltranslator/translations/` | 翻訳結果の永続キャッシュ(ワールドセーブ内) |

## 対応プロバイダと既知の制限

- **OpenAI互換**: 実機で動作確認済み。
- **Google Translate Web版(無償・非公式)**: 実機で動作確認済み。非公式エンドポイントのため、
  Google側の仕様変更で将来動作しなくなる可能性があります。
- **DeepL / Google Cloud Translation v2**: 実装済みですが、実際のAPIキーでの
  疎通確認は行っていません(公式ドキュメントに基づくモックサーバーでのレスポンス
  形状検証のみ)。ご利用の際は少量のテキストで動作確認してからお使いください。
- **FTB Quests連携**: 本Mod対象のMinecraftバージョン向けの公式リリースが
  現時点で存在しないため未実装です(導入されていても未導入判定のみでクラッシュはしません)。

## 開発

Architectury(common/fabric/neoforge)構成。ビルドは以下:

```bash
./gradlew build
```

## ライセンス

(ライセンスを記載してください)

<!-- AT-KNOWN-LIMITATIONS-PHASE13 -->
## 既知の制限 (Phase 13時点)

- **libIPNベースの設定画面は翻訳対象外**: vanillaの`AbstractWidget`を使わず独自の`Renderable`実装でUIを描画するMod(libIPN等)のウィジェットは、`Screen#children()`経由での翻訳フックが届かないため未対応です。
- **Anthropic(Claude)・Geminiプロバイダ**: 実際のAPIキーでの動作確認は未実施です。
- **DeepL・Google Cloud Translation v2**: ローカルモックサーバーによるレスポンス形状検証のみで、実際の本番エンドポイントでの疎通確認は未実施です。
- **サーバー側チャット翻訳(`serverSideChatTranslationEnabled: true`)**: マルチプレイ環境での実翻訳動作確認は未実施です。
