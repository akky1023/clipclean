# ClipClean

X、Instagram、YouTube のURLから共有情報などのクエリパラメータを削除します。処理は端末内で行われます。短縮URLは元の形式のまま使えます。

## Windows

1. `ClipClean-Windows.zip` を展開し、`ClipClean/ClipClean.exe` を実行します。
2. URLをコピーすると、整理したURLがクリップボードに入ります。
3. 通知領域のアイコンから設定、一時停止、終了を選べます。

設定で「Windows ログイン時に自動起動」を ON にすると、今のユーザーのスタートアップにショートカットを登録します。展開フォルダーを移動した場合は、新しい場所から一度起動してください。

## Android

Android 8.0 以降で `ClipClean-Android-debug.apk` をインストールできます。共有メニューの「ClipClean で整理してコピー」を選ぶか、URLをコピーした後で ClipClean を開いて「クリップボードを整理」を押します。

## 設定

- 「削除するパラメータ」で ON にした項目を削除します。初期設定では YouTube の再生開始時刻とプレイリスト指定を保持します。
- YouTube の動画IDとプレイリスト自体のIDは常に保持します。リストにない項目も初期設定では保持します。
- 「文章や複数行に含まれるURLも整理する」を ON にすると、共有文の中にある対応URLも処理します。
- 設定のエクスポートとインポートで、同じ JSON ファイルを Windows と Android で使えます。

## ビルド

Go 1.26 以降で共通処理をテストできます。

```powershell
go test ./...
```

### Windows

Windows の PowerShell で実行すると、`dist/ClipClean-Windows.zip` が生成されます。ビルドと実行に Java は不要です。

```powershell
.\windows\package.ps1
```

### Android

JDK 17、Gradle 8.11.1、Android SDK Platform 35、Android NDK r28 以降を用意し、`JAVA_HOME` と `ANDROID_HOME` を設定します。サイドバイサイドでインストールした NDK は自動検出されます。`ANDROID_NDK_HOME` で個別に指定できます。Go Mobile は Go モジュールに記録されたバージョンを Gradle が呼び出します。

```powershell
gradle :android-app:debugApk
```

`dist/ClipClean-Android-debug.apk` が生成されます。Android の画面、共有メニュー、クリップボード連携には Java を使い、URL整理と設定処理は Windows と同じ Go コードを呼び出します。

### Go から利用する

`net.neomenia/clipclean/core` の `core.Clean(text, settings)` で、整理後の文字列、対応URLの有無、変更の有無を取得できます。`core.Settings{}` は初期設定です。`core.DecodeSettings` に既存の設定JSONを渡すと、保存済みの設定を使えます。
