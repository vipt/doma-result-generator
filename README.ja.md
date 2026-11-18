# Doma Result Generator

[インストール・更新サイト](https://vipt.github.io/doma-result-generator/) · [English](README.md) · [Apache-2.0](LICENSE)

SELECTの結果を格納するDoma 2用のJavaクラスを生成する、独立したコミュニティ製のEclipseプラグインです。JOINや集計など、1つのテーブルと対応しない検索結果に利用できます。

**初期試作版です。** H2とDoma 2.57.0でコア部分をテストします。他DBへの対応はJDBCドライバーの実行前メタデータ取得機能に依存します。Doma・Eclipseの公式製品ではありません。

## 使い方

1. EclipseのJavaプロジェクト内でSQLファイルを保存します。
2. Package Explorer・Project ExplorerのSQLファイル、または開いているSQLファイルのエディター上で右クリックし、**Generate Doma Result Class...**を選びます。
3. **Connection profile**で登録済みの接続を選ぶか、JDBCドライバーのJAR、ドライバークラス、接続URL、ユーザー名・パスワードを入力します。依存JARがある場合は複数指定できます。
4. 出力先のソースフォルダー、パッケージ、クラス名を指定します。名前から型を指定する場合は**Type mappings (optional)**で設定ファイルを選びます。プロジェクト直下に`name.properties`があれば初期選択されます。
5. **Metadata SQL**でテンプレートを確認し、必要な型付き引数を**Arguments**に入力して**Preview**を押します。
6. **Proposed Java**で結果を確認します。新規クラスは**Generate**、既存クラスは差分を確認して**Update**を押します。

`@Entity`、列の別名に対応する`@Column`、privateフィールド、publicのgetter/setterを生成します。アノテーションとプロパティの型をimportし、`@Column`・`BigDecimal`・`LocalDateTime`などの短い型名で出力します。`java.lang`の型と、エンティティと同じパッケージの型はimport不要です。同名の型や手動コードと衝突する場合は、その参照だけ完全修飾名を使います。既存クラスは差分確認後に更新できます。DAOやプロジェクト設定は変更しないため、生成したクラスをDAOの戻り値に指定してください。

接続プロファイルは同じワークスペースのプロジェクト間で共有できます。プロジェクトごとに最後に選択したプロファイルとPackageを保持し、**Cancel**で閉じた場合やEclipse再起動後も復元します。**Unregistered (project settings)**では、従来どおりDriver JARs・Driver class・JDBC URL・Usernameをプロジェクト別に保持します。Packageは前回入力した文字列を初期値にし、初回は`com.example.dto`です。プロジェクト内のファイルには書き込みません。ソースフォルダー・クラス名・編集したMetadata SQLは保持しません。

SQLエディターから起動した場合、Eclipseのテキストバッファーにある編集中のSQLを使用し、元のファイルは保存しません。未保存の編集を取得できないエディターでは、SQLの保存を求めます。Explorerから起動した場合は保存済みのSQLを使用します。

`.SQL`・`.Sql`などの大文字小文字が混在する拡張子にも対応し、`selectAll.SQL`の初期クラス名は`SelectAllResult`になります。

パスワードは**Save password in Eclipse Secure Storage**で保存するかを選べます。初期状態ではオフです。オンの場合、Password欄を[EclipseのSecure Storage](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/secure_storage_architecture.htm)に暗号化して保存します。Eclipseからロック解除を求められる場合があります。チェックを外すかPassword欄を空にし、プロファイルでは**Update profile**、未登録設定ではダイアログを閉じると、対象の保存済みパスワードを削除します。通常の設定ファイルにはパスワードを書き込みません。Driver class・JDBC URL・Usernameを変更すると、復元済みのパスワードをクリアします。認証情報はUsername・Password欄へ入力してください。Oracleの`user/password@...`や`password=...`、URLのユーザー情報など、認証情報を含むURLは保持しません。Secure Storageの読み込みや更新に失敗した場合は通知し、平文での代替保存は行いません。

ドライバーは同梱せず、利用者が別途用意します。DBエラーでは、失敗した段階、例外クラス、SQLState、ベンダーエラー番号、取得できる場合はOracleのORA番号を表示します。**Error details...**から詳細を確認できます。接続情報を含む可能性があるドライバーの例外本文は表示・記録しません。

### 接続プロファイルの登録と共有

1. 既存の接続設定があるプロジェクトで生成ダイアログを開きます。**Connection profile → Unregistered (project settings)**に従来の接続情報が表示されます。保存済みパスワードも、Secure Storageから読み込めればそのまま利用できます。
2. **Save as...**を押し、「開発DB」「検証DB」などの名前を付けます。表示中のDriver JARs・Driver class・JDBC URL・Username・Preview timeoutと、パスワード保存の選択を登録します。再入力は不要です。
3. 別プロジェクトで生成ダイアログを開き、**Connection profile**から登録した名前を選びます。接続設定が一括で入り、以降はそのプロジェクトの前回選択を復元します。

共有設定を編集した場合は**Update profile**で確定します。同じプロファイルを使う他のプロジェクトでも、次に開いたときに変更が反映されます。別の接続として残す場合は**Save as...**を使います。生成ダイアログを閉じるだけでは共有設定を上書きしません。Package・Class name・出力先・型マッピングファイル・Metadata SQLは共有しません。

**Manage...**では名前変更・削除ができます。名前変更後もプロジェクトの選択を維持します。削除すると、そのプロファイル専用の保存パスワードも削除し、使用していたプロジェクトは未登録設定に戻ります。登録元のプロジェクト別設定は残します。

別ワークスペースへ移す場合は、管理画面の**Export all... / Import...**を使います。UTF-8のpropertiesファイルに接続URL・ユーザー名・ローカルのJARパスなどを出力しますが、パスワードは含めません。インポート後はパスワードを入力し、JARパスを確認してください。同名のプロファイルがある場合は名前に連番を付けて追加し、既存設定を上書きしません。

### 接続待ちのキャンセルとタイムアウト

**Preview**中は進捗ダイアログの**Cancel**で待機を中止できます。DBへの接続が返らない場合も、JDBCドライバーの終了を待たずに生成ダイアログへ戻ります。Javaファイルは変更せず、設定を直して再度Previewできます。

**Configuration → Preview timeout (seconds)**でプレビュー全体の待ち時間の上限を指定できます。初期値は**30秒**、範囲は1～600秒です。接続・メタデータ取得・クラス生成・既存クラスの更新案作成を含みます。プロファイル利用時はUpdate profileで保存し、未登録設定ではプロジェクトごとに保持します。Oracleドライバーでは、同じ秒数を接続とソケット読み取りのタイムアウトにも設定します。他のドライバー固有のタイムアウトは、各ドライバーの説明に従ってJDBC URLで指定してください。

キャンセルや時間切れでは、処理への割り込み、ステートメントのキャンセル、取得済み接続の中断を試みます。ドライバーが応答しない場合はバックグラウンドで終了を待ちますが、遅れて返った結果は適用せず、接続は解放します。終了待ちが2件残っている間は追加のPreviewを受け付けず、その旨を表示します。

### macOSでパスワードを保存できない場合

保存時に`Connection settings or the saved password could not be updated.`と表示される場合、macOSのキーチェーンからSecure Storage用のマスターパスワードを取得できていない可能性があります。入力画面やキーチェーンのアクセス許可画面が表示されないまま失敗する場合もあります。Eclipseのログに`OSXProvider`のエラー（例：`Could not obtain password. Result: -25300`）がある場合は、次の手順で**UI Prompt**への切り替えを試してください。

1. Eclipseの設定（macOSでは通常`⌘,`）を開きます。
2. **General → Security → Secure Storage → Password**を開きます。
3. **Master password providers**で**OS X Keystore Integration**のチェックを外し、**UI Prompt**を有効にします。
4. **Apply and Close**で適用します。生成ダイアログを開き、DBのPasswordを再入力して**Save password in Eclipse Secure Storage**をオンにします。接続プロファイルでは**Update profile**で保存します。未登録設定ではダイアログを閉じると保存し、**Cancel**でも保存します。
5. 表示される入力画面で、Eclipseの保存情報を暗号化するためのマスターパスワードを設定または入力します。その後、生成ダイアログを開き直してPasswordが復元されることを確認します。

**UI PromptでもSecure Storageによる暗号化保存を利用します。**変更するのはマスターパスワードの取得方法です。この設定はEclipse全体で新しく保存する情報に影響し、既存の保存情報は自動移行されません。詳しくは[EclipseのSecure Storage設定](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-prefs.htm)と[動作の説明](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/reference/ref-securestorage-works.htm)を参照してください。

## 既存クラスの差分プレビューと更新

既存クラスと同じソースフォルダー・パッケージ・クラス名を指定して**Preview**を押すと、変更前の**Current saved class**と変更後の**Proposed update**を並べたEclipseの比較画面を開きます。比較画面は読み取り専用です。確認して比較画面を閉じ、生成ダイアログの**Update**を押すと反映します。**Compare changes...**から再確認でき、**Proposed Java**では更新後のファイル全体を確認できます。比較画面を閉じただけの場合や、生成ダイアログをキャンセルした場合はJavaファイルを変更しません。変更がなければUpdateは無効になります。

生成するフィールド・getter/setterには、管理対象を示すJavadocタグ`@domaGenerated`を付けます。更新時はJavaの構文情報とソース上の位置を使って、必要な箇所を変更します。

- SELECT列の追加に応じてフィールドとアクセサーを追加します。型変更ではフィールドと単純なgetter/setterの型を更新します。`name.properties`の指定も反映します。
- 手動で追加したメソッド、コンストラクター、フィールド、import、クラスのアノテーション、内部クラスなどを保持します。手動で変更したアクセサーの本体も、型の整合性を保てる場合は保持します。
- Javadoc・行コメント・ブロックコメントを保持します。不要になった生成メンバーを削除する場合も、その中のユーザーコメントは独立したコメントとして残します。管理用タグは削除対象の宣言とともに除きます。
- 削除する列のフィールドを手動コードが参照している場合、手動変更されたアクセサーの型変更が必要な場合、手動メンバーと衝突する場合などは、理由を表示して更新を止めます。
- 更新案を保存しないJDTの作業コピーで検査します。対象プロジェクトのビルドパスを使い、手動メソッドを含む型エラーやJavaエラーがあれば更新を止めます。
- ワイルドカードimportの型は対象プロジェクトから解決します。同名の型が複数あって曖昧な場合は更新を止め、明示的なimportまたは完全修飾名を求めます。生成メンバーで使っていた通常のimportは、コード・Javadocのどこからも参照されなくなった場合に削除します。それ以外の手動importやstatic・ワイルドカードimportは保持します。

UTF-8 BOM付きの既存クラスも更新でき、BOMは保存時に維持します。

既存ファイルの編集は、プレビュー前に保存してください。プレビュー後にファイルが変更された場合や、エディターに未保存の編集がある場合は更新せず、プレビューのやり直しを求めます。文字コードを維持して保存し、更新前の内容はEclipseのローカル履歴を保持する指定で保存します。履歴の保持期間などはワークスペースの設定に従います。比較・検査の対象はそのJavaファイルであり、公開メソッドの型変更に伴う他ファイルの呼び出し元の修正は別途必要になることがあります。

### 旧版で生成したクラス

元の`Generated from SELECT result metadata.`というクラスコメントがあり、管理タグがまだない場合は、現在のSQLと一致する単純な`@Column`付きフィールドと通常のアクセサーを引き継ぎます。コメントや手動メソッドは残し、引き継いだメンバーに管理タグを追加します。

現在のSQLにない旧フィールドは、生成元を確定できないため残してプレビューの案内に表示します。これらの項目は手動で確認してください。管理対象と識別できない同名フィールドや、immutableエンティティ、特殊な宣言は自動更新せず、調整を求めます。

## 対応範囲

- 特定DBに固定せず、JDBCの`PreparedStatement.getMetaData()`で列ラベル・型を取得します。
- JOIN・集計・CTEの結果に対応します。空の検索結果でもメタデータを取得できます。
- SQLの実行や検索結果の取得を行うAPIは呼び出しません。ただし、ドライバー内部では準備・解析のためにDB通信が発生する場合があります。
- 列名をキャメルケースに変換し、別名を`@Column`に保持します。Javaの予約語やフィールド名の衝突にも対応します。
- NULLに対応できるラッパー型、BigDecimal、String、byte[]、LocalDate/LocalTime/LocalDateTime、LOB型などを生成します。
- 詳細な型対応表は[英語README](README.md#type-mapping)を参照してください。

### Doma標準の評価とArguments

Doma Core 2.57.0の**`SqlParser`・`ExpressionEvaluator`・`NodePreparedSqlBuilder`・`PreparedSqlParameterBinder`をそのまま呼び出します。** 独自の分岐選択、条件本文の強制追加、サンプル要素によるループ、定数だけに制限した評価はありません。指定した引数で`%if/%elseif/%else`・`%for`・ループ制御変数・バインド・リテラル・埋め込みSQL・標準関数・アプリケーションのメソッドを評価します。

**Arguments**タブに型付きJSONを入力するか、**Load JSON...**で読み込みます。

```json
{
  "ids": {"type": "java.util.List<java.lang.String>", "value": ["A", "B"]},
  "enabled": {"type": "boolean", "value": true},
  "orderBy": {"type": "java.lang.String", "value": "ORDER BY name"},
  "minimum": {"type": "java.lang.Long", "value": null}
}
```

引数ごとにJavaの`type`とJSONの`value`を指定します。原則として完全修飾型名を使い、java.langの型は短い名前も使えます。コレクションの要素型やnullの型も明示してください。プリミティブ型にnullは指定できません。配列・BigDecimal/BigInteger・ISO形式のLocalDate/LocalTime/LocalDateTime・java.sqlの日時型にも対応します。必要な引数がなければDomaのエラーを表示し、SQLのテスト値や推測値で補いません。選択されない分岐だけで使う引数は不要です。

条件オブジェクトにはコンパイル済みの型と、そのフィールドのJSONを指定します。

```json
{
  "condition": {
    "type": "example.SearchCondition",
    "value": {"name": "sample", "enabled": true}
  }
}
```

**Use compiled project classes and dependencies for expressions**をオンにすると、参照プロジェクトを含む実行時クラスパスを読み込みます。先にプロジェクトをビルドし、そのクラスを読み込めるJavaでEclipseを起動してください。追加のJAR・クラスフォルダーは**Additional expression JARs/folders**にセミコロン区切りで指定します。Gsonでオブジェクトの値を構築し、Doma本体がプロパティ・フィールド・enum・メソッドを参照します。Previewではコンストラクター・getter・static初期化子・指定されたメソッドが実行される場合があります。標準関数はJDBC URLに対応するDomaのDialectを使います。アプリケーションやDIコンテナーの起動、DAO独自Configの自動再現は行いません。

引数と式評価用クラスパスの編集内容は、生成ダイアログを開いている間だけ保持します。接続プロファイル・通常設定・プロジェクトファイル・ログには保存しません。読み込んだJSONファイルも変更しません。エラーはDomaのコードと、取得できる場合は不足する引数名・前処理後SQLの行番号を表示し、SQL本文・値・アプリケーションの例外本文は表示しません。呼び出したアプリケーションメソッド自身のログ出力・副作用は、そのコードの動作に従います。

### 評価後のSQLと列情報

```sql
SELECT e.id, e.name FROM employee e
WHERE (
  /*%for item : ids */
    e.id = /*item*/'ignored'
    /*%if item_has_next */ /*# "OR" */ /*%end*/
  /*%end*/
)
/*# orderBy */
```

指定したリストの実際の要素数でループし、`item_has_next`・`item_index`もDoma本体が評価します。バインドコメントは`?`になり、指定した値をDoma標準のバインダーで設定してからJDBCメタデータを取得します。SQL内の`'ignored'`などのテスト値はDomaが無視します。取得列も指定した引数で選ばれるため、未選択の分岐を自動比較しません。引数で列構成が変わる場合は、生成先クラスや指定する引数を使い分けてください。従来の64通り・256サンプル要素という制限とサンプル展開は削除し、Preview全体のタイムアウト・キャンセルは引き続き適用します。

**Evaluated SQL**には、Domaが組み立てたSELECTを表示します。バインド値は`?`で伏せます。評価後にDB接続・メタデータ取得が失敗した場合も確認できます。**Metadata SQL**は元のテンプレートを保持し、保存済みSQLファイルは変更しません。SELECTの実行・結果取得APIは呼び出さず、選択された列構成だけを確認します。

`%expand`とその別名の式もDoma本体で処理します。生成先エンティティが未作成のため、列展開コールバックには`*`を渡し、DBに`*`・`alias.*`の列情報を問い合わせます。既存エンティティ定義から列一覧を得る方式ではありません。展開指定の直後に`*`を置くなど、Doma標準の構文に従ってください。説明用コメントには`/** ... */`・`-- ...`を使います。`/* name */`はDomaのバインド式であり、直後のテストリテラルが必要です。

**Metadata SQL → SQL syntax**はJDBC URLから引用・コメントの扱いを選び、PostgreSQL・MySQL/MariaDB・SQL Server・Oracleなど向けの手動指定も可能です。DB固有の文字列・引用識別子・通常コメントは保護しますが、Domaの指示や式を置き換えて評価を省略することはありません。SELECTのみのチェックと、JDBC向けのCTE区切りはテンプレート評価とは別に残します。

SQL ServerのCTEでは、ドライバーが付ける`SET FMTONLY ON`を終える`;`をJDBC用SQLにだけ補います。既存の`;WITH`や末尾の重複した`;`・コメントも受け付け、元ファイルは変更しません。実際の別クエリや更新文は拒否します。ロック句付きSELECTも実行せず準備・列情報取得のみ行います。[Microsoft JDBCの実装](https://github.com/microsoft/mssql-jdbc/blob/v12.8.1/src/main/java/com/microsoft/sqlserver/jdbc/SQLServerPreparedStatement.java)を参照しています。

SQLStateだけではOracleエラーの原因を特定できないことがあります。**Error details...**でORA番号・ベンダー番号・失敗した段階を確認してください。同じSQLがSQLクライアントで実行できる場合は、JDBCドライバーや接続セッションの現在のスキーマなどの違いを切り分けます。不具合報告にはコードと段階のみを使い、接続情報やパスワードを含めないでください。

既存クラスのマージ・Java検査のエラーは、それぞれの段階を表示します。JDBCドライバーの解放エラーとは区別します。

実行前のメタデータ取得に対応しないドライバーは初版の対象外です。JDBCのOTHER・ARRAY型やタイムゾーン付き日時、DB独自型に既定のマッピングがない場合は、名前による型指定またはSQLのCASTを検討してください。独自ドメイン型の自動推定は行いませんが、設定ファイルで明示指定できます。同じ列名が複数ある場合はASで区別します。Domaの分岐間でSELECT列の名前・順序・型が異なるSQLは、生成対象の列構成を明示する必要があります。

## 名前による型指定（name.properties）

doma-genと同じ形式で、生成するJavaのプロパティ名に正規表現で型を指定できます。UTF-8のファイルを**Type mappings (optional)**から選択してください。プロジェクト直下の`name.properties`は、存在する場合に初期選択されます。別のファイル名でも使用できます。

```properties
.*Datetime$=java.time.LocalDateTime
```

たとえばSQLの列名・別名が`CREATED_DATETIME`なら、Java側の名前は`createdDatetime`になり、上のルールで型を指定できます。

```java
import java.time.LocalDateTime;
import org.seasar.doma.Column;

// エンティティクラス内
@Column(name = "CREATED_DATETIME")
private LocalDateTime createdDatetime;
```

- 照合対象は、キャメルケースへの変換や重複回避の接尾辞付与が済んだ**Javaのプロパティ名**です。
- 正規表現は大文字・小文字を区別し、名前全体に照合します。`createdDateTime`は`.*Datetime$`に一致しません。
- ファイルの**上から最初に一致したルールを優先**し、JDBCの既定の型を上書きします。一致しないプロパティはJDBCの型を使用します。
- `com.example.dto.EmployeeResult@.*Datetime$=java.time.LocalDateTime`のように、特定クラスだけに適用する指定もできます。クラス名部分は文字通りの名前、`@`以降は正規表現です。限定したルールは広いルールより上に置いてください。
- `$1`等のキャプチャー参照、先頭`@`によるプロパティ正規表現の指定に対応します。
- 空行、`#`・`!`で始まるコメントを使用できます。最後の`=`で正規表現と型指定を区切り、前後の空白は除去します。`(?=.*Id$)user.*=java.lang.Long`のように正規表現中にも`=`を使えます。正規表現のバックスラッシュは保持します。置換は名前全体への一致に対して行い、最短一致や選択パターンでも名前の残りを型名に追加しません。
- 型は完全修飾クラス名、プリミティブ型、`byte[]`を指定できます。ジェネリクス式とその他の配列表記は対象外です。不正な正規表現や型名は行番号付きで表示します。

`employeeId=com.example.domain.EmployeeId`のように、対象プロジェクトのDomaドメイン型も指定できます。指定型が存在し、Domaで利用でき、JDBCから値を取得できる必要があります。設定によって型名を変更しても、独自の値変換処理が追加されるわけではありません。NULLがあり得る場合はラッパー型を使ってください。

設定ファイルは**Preview**を押すたびに読み直します。編集後は保存してからプレビューしてください。設定欄を空にするとJDBCの既定の型だけを使用します。[設定例](examples/name.properties)も用意しています。

## ビルド・インストール

開発・実行環境はJava 17以上、Eclipseは2023-12 / 4.30以降を対象とします。ビルドは同梱のApache Maven WrapperでMaven 3.8.6を使用します。Windowsでは`mvnw.cmd`を使ってください。生成クラスのJava構文・日時型はJava 8に対応します。

公開版は、Eclipseの **Help → Install New Software... → Add...** で次のURLを指定するとインストールできます。

```text
https://vipt.github.io/doma-result-generator/
```

**Doma Result Generator**を選び、内容とライセンスを確認してインストール後に再起動してください。以降は **Help → Check for Updates** から更新できます。[導入ページ](https://vipt.github.io/doma-result-generator/)からZIP形式でも取得できます。

ソースからビルドする場合:

```sh
./mvnw --batch-mode --no-transfer-progress verify
```

`repository/target/doma-result-generator-repository-0.1.0-SNAPSHOT.zip`が更新サイトのZIPになります。Eclipseの**Help → Install New Software... → Add... → Archive...**から選び、インストール後に再起動します。対象のJavaプロジェクトではDomaの依存関係とアノテーションプロセッサーを設定しておいてください。

Eclipse本体を取得せずにコアのテストだけ実行する場合:

```sh
./mvnw -f tests/pom.xml test
```

## 公式生成ツールとの関係

[doma-gen](https://github.com/domaframework/doma-gen)はアーカイブ済みで、後継の[doma-codegen-plugin](https://github.com/domaframework/doma-codegen-plugin)が案内されています。Doma 2向けは後継ツールの2系です。公式にもSQLの結果から生成する機能があり、このプラグインではEclipse上でのSQL選択・接続設定・プレビュー・ファイル生成を提供します。

現在は**doma-gen 2.28.0の`EntityDescFactory`・`EntityPropertyDescFactory`・`Generator`を直接呼び出します**。privateフィールド、`@domaGenerated`管理タグ、型名の衝突対策にはEclipse向けの出力テンプレートとアダプターを使います。SQLテンプレートの解析・評価・組み立て・バインドには**Doma Core 2.57.0の`SqlParser`・`ExpressionEvaluator`・`NodePreparedSqlBuilder`・`PreparedSqlParameterBinder`をそのまま使用します**。

Eclipseの接続プロファイル、プレビュー、既存クラスへの差分更新はプラグインが担当します。公式ライブラリと依存ライブラリは更新サイトに同梱し、GradleやAntコマンドの別途インストールは不要です。Antタスクとしてdoma-gen全体を起動する方式ではありません。

Argumentsとコンパイル済みプロジェクトクラスをDoma標準の評価器へ渡します。独自の分岐選択・サンプルループはありません。アプリケーションやDIコンテナーの起動は行いません。公式doma-genの`ResultSetMetaReader`はSELECTを実行するため呼び出さず、列情報は引き続きJDBCの実行前メタデータで取得します。型マッピング・名前規則と手動コード保持の仕様も維持します。詳細と参照ソースは[英語README](README.md#relationship-to-the-official-generator)、再配布ライブラリは[THIRD_PARTY_NOTICES](plugin/THIRD_PARTY_NOTICES)に記載しています。

## オープンソースとしての公開

ソースの公開先は[vipt/doma-result-generator](https://github.com/vipt/doma-result-generator)、案内ページ兼Eclipse更新サイトは[GitHub Pages](https://vipt.github.io/doma-result-generator/)です。Apache License 2.0で配布します。初期プレビュー版として、確認済みの範囲と未検証の項目を導入ページに記載しています。

GitHub Actionsの **Build and deploy Pages** が、`main`へのpush時にビルド・テストを実行し、成功した場合に案内ページと更新サイトを公開します。Pull Requestではテストだけを実行します。GitHubの **Settings → Pages → Source** は **GitHub Actions** を使います。テスト結果と更新サイトZIPもActionsの成果物に保存します。GitHub Releasesの作成は別途行います。

初回公開前には[CONTRIBUTING.md](CONTRIBUTING.md)のEclipse実機確認と、公開時に対応を表明するDB・ドライバーの組み合わせで確認してください。今後は、接続設定の再利用、Domaの動的SQLの展開などを拡張できます。
