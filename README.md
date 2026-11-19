# My Note App（Android 原生版）

獨立的 Android Studio / Gradle 專案，連線至既有的 [MyNote](https://mynote.christorng.workers.dev/) Cloudflare Worker。**不修改原有網站、資料庫或 API**；不會取代 Todo。

## 設計

- **低記憶體與快速開啟**：原生 Java + Android SDK，沒有 WebView、Compose、AndroidX、第三方執行階段程式庫、背景常駐服務或喚醒鎖。`ListView` 會重用列項，避免為每筆筆記配置完整畫面。
- **獨立 App**：安裝名稱「My Note App」，與瀏覽器及 PWA 是不同的應用程式。
- **真正的新增捷徑**：長按 App 圖示的「新增筆記」快捷動作，或從 App 右上角選單選「建立桌面新增捷徑」。快捷動作直接進入原生新增畫面、游標位於編輯框並嘗試顯示鍵盤，不先載入列表。
- **離線優先**：SQLite 本機保存；輸入後 550ms 自動儲存，切換 App 或離開編輯器時立即落盤；建立／修改／封存／刪除佇列在恢復連線後同步。
- **原資料互通**：沿用 `/api/login`、`/api/state`、`/api/note`、`/api/archive`、`/api/category`、`/api/device`。可瀏覽既有所有筆記；跨裝置仍遵循「建立端可編輯，其他端唯讀」，但各端均能封存。
- **登入**：重用既有存取碼、180 天滑動工作階段。Cookie Session 使用 Android Keystore AES-GCM 加密後保存在 SharedPreferences。

## 第一版功能

原生登入、快速新增、一般新增、編輯、複製整筆、搜尋內文／裝置／日期、分類的新增／更名／刪除、Current／Archive、封存／還原／刪除、連結點擊（唯讀筆記）、裝置更名、工作階段重新登入；原本的資料不用移轉。

本版未實作網頁版的桌面三欄寬度調整、WebSocket 推播、完整 PWA 安裝行為與逐項 UI 版面對照。筆記修改由本機排程同步，App 開啟期間每 60 秒拉取一次更新；背景不常駐。

## 取得 APK

**方法 A — GitHub Actions**

將此專案的所有檔案放到一個新 GitHub Repo 的 `main` 分支（例如 `ChrisTorng/My-Note-App`）。推送後，進入 **Actions → Android APK → 最新成功的 Run → Artifacts → My-Note-App-debug**，下載壓縮檔，解壓後取得 `app-debug.apk`，安裝到 Android。也可以在 Actions 手動執行 `workflow_dispatch`。

**方法 B — Android Studio**

在 Windows 開啟 Android Studio → **Open** → 選擇此專案目錄 → 等待 Gradle 同步 → **Build → Build App Bundle(s) / APK(s) → Build APK(s)**。輸出的測試版位於 `app/build/outputs/apk/debug/app-debug.apk`。

- JDK 17
- Android Gradle Plugin 8.9.2、Gradle 8.13
- compileSdk 35、targetSdk 35、minSdk 26
- 本版使用既有 Worker 網址 `https://mynote.christorng.workers.dev`，不需要改 Worker 設定

## 使用

1. 安裝 APK，使用**原 MyNote 存取碼**登入，指定此 App 的裝置名稱。
2. 建立桌面捷徑：App 選單 → **建立桌面新增捷徑**，同意系統新增；或長按 App 圖示並將「新增筆記」拖到桌面。
3. 點桌面捷徑，直接輸入；關閉畫面也會保存本機變更，回到線上後自動同步。

## 限制與注意事項

- **目前尚未在 Android 實機或 Android SDK 環境編譯測試**；此專案屬第一版原始碼，不能視為已完成的實機驗收版。
- 新 App 會取得一個**獨立裝置 ID**：過去在 Chrome／PWA 建立的筆記對此 App 為唯讀，仍可以複製、封存、還原；這是目前後端權限規則，並非資料遺失。要編輯舊筆記，仍需用原建立端。
- SQLite 內的離線筆記是本機明文資料（由 Android 裝置儲存空間加密保護）；停用 App 備份以降低個人資料外流風險。**卸載或清除 App 資料前，務必確認所有筆記已同步**。
- 工作階段使用 Android Keystore 保護；伺服器仍依原站設計保存明文筆記，本 App 不新增端對端加密。
- 遇到伺服器拒絕更新、版本衝突或離線時，未同步的本機變更不會因為拉取遠端資料而直接覆蓋；應先處理同步錯誤，勿清除 App 資料。
- 效能與記憶體必須以實機 `adb shell dumpsys meminfo tw.idv.christorng.mynoteapp` 比對 PSS / RSS / 啟動時間；**尚無測得的 MB 數據**。
