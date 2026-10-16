# 操作確認ガイド

## 環境と起動

Java 25、MySQL、Maven Wrapperを使用する。外部CDNのjQuery及びEChartsへ接続できる環境を用意する。
本番のDBやアカウントをデモの更新試験へ流用しない。書込確認は隔離した検証DB・合成社員で行う。

既存DBを利用する場合は適用済みSQLと設定を確認し、移行を重ねて実行しない。
空のDBは[DB適用手順](../src/main/resources/database/README.md)の基礎表と移行の準備が必要であり、アプリ起動だけで自動作成しない。
サンプルの接続先・利用者・パスワードを実値に置き換え、非公開の設定ファイルで保持する。

```powershell
.\mvnw.cmd test
.\mvnw.cmd -DskipTests package
.\scripts\start-oa.ps1 -Configuration 'D:\oa-private\application.properties'
```

接続設定をJARへ同梱しない。`application.properties`を省いた配布JARを使用する。
設定ファイルを作る場合も、既存の非公開設定へサンプルを上書きしない。
本機の`D:\oa-maintenance`やクラウド接続情報が、提出先に存在する前提にしない。

### 接続設定とCA

DB接続は`sslMode=VERIFY_IDENTITY`とし、接続先が配布したCAを専用トラストストアへ登録する。
例示コマンドは公開CAだけを新規の容器へ登録するもので、Windows共通の信頼ストアを変更しない。
指定先の親フォルダーを事前に用意し、既存トラストストアを上書きしない。

```powershell
keytool -importcert -alias project-ca -file 'D:\oa-private\ca.pem' -keystore 'D:\oa-private\project-ca.p12' -storetype PKCS12 -storepass changeit
```

CAの出所・指紋を確認してから登録する。公開CA容器の`changeit`とDB接続パスワードは別物である。
JDBC URLでは`trustCertificateKeyStoreUrl`・`trustCertificateKeyStoreType=PKCS12`・`trustCertificateKeyStorePassword`を指定し、
`fallbackToSystemTrustStore=false`とする。設定例はREADMEを参照する。

独立管理コンソールは`security-console/README.md`に従い、本人PCの非公開設定・HTTPS証明書を用意する。
既存の本人設定を初期化し直さない。既定URLは`https://localhost:9443/`であり、別PCのlocalhostからは接続できない。
自己署名証明書は指紋を照合して本人が判断し、証明書検証を無効にしない。

## 役割別の確認順序

| 順序 | アカウント | 確認する内容 |
| --- | --- | --- |
| 1 | 一般社員 | ログイン、本人情報、勤怠・休憩、申請、本人履歴、公開公告 |
| 2 | 総務担当 | 対象申請の審査、他者の勤怠、公告の編集。従業員・部署管理へ入れないこと |
| 3 | 営業利用を許可された部署の社員 | 自部署の営業実績保存・編集。他部署の実績操作を拒否すること |
| 4 | 管理者 | 営業反映後のダッシュボード、従業員・部署・勤務設定。最後の有効管理者の降権を拒否すること |
| 5 | 独立管理の監視担当 | 参照のみ。運用操作・担当者管理・昇格を拒否すること |
| 6 | 独立管理の運用担当 | 許可された保護操作、上位操作の審査申請、自己承認禁止 |
| 7 | 独立管理の最高管理者 | 担当者状態・審査・会話。OA社員や監査原本を任意編集できないこと |

試験データには識別可能な接頭辞を付ける。実社員への権限変更、実申請の審査、実管理者の失敗蓄積を試験として行わない。
パスワード・認証器・招待コード・Cookie・秘密鍵をスクリーンショットや提出資料へ含めない。

## 画面・整合の確認

- 入力不備は値を保持して欄内へ日本語表示し、ブラウザー標準のalert／confirm／promptを出さない。
- 保存・承認・検索・ページ送りでは必要な領域だけを更新し、別フォームの未保存入力を失わない。
- 保存成功後の再取得失敗はGETだけをやり直し、POSTを自動再送しない。
- 打刻・休憩の時系列、休日出勤の承認、他人の申請・自己承認・二重処理を確認する。
- 部署の営業許可停止・異動・権限変更と保存が競合した場合も、最新権限で拒否する。
- 版数競合・監査障害では更新を中止し、成功として表示しない。

## 自動試験

通常のJava試験、AJAX試験、専用DBの受入試験を混同しない。
[AJAX試験](../src/test/js/README.md)と[隔離受入試験](../src/test/java/com/example/demo/acceptance/README.md)のガード・前提に従う。
専用DBの試験には本番接続情報を渡さない。実行ポート・データ領域・DB名・識別行を照合し、両アプリのfixture試験は順次実行する。
