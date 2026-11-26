# Doma Result Generator

[インストール・更新サイト](https://vipt.github.io/doma-result-generator/) · [English](README.md) · [Apache-2.0](LICENSE)

SELECTの結果を格納するDoma 2用のJavaクラスを生成する、独立したコミュニティ製のEclipseプラグインです。JOINや集計など、1つのテーブルと対応しない検索結果に利用できます。

DBのテーブルを選択し、公式**doma-gen 2.28.0**でDAO・Entity・SQLと、必要に応じてEntityListenerを生成する機能もあります。

**初期試作版です。** H2とDoma 2.57.0でコア部分をテストします。他DBとの互換性はJDBCドライバーと公式doma-genの方言に依存します。Doma・Eclipseの公式製品ではありません。

## 使い方

以下はSELECT結果クラスの生成手順です。テーブルからの生成は[テーブルからDAO・Entity・SQLを生成](#table-generation)を参照してください。

1. EclipseのJavaプロジェクト内でSQLファイルを保存します。
2. Package Explorer・Project ExplorerのSQLファイル、または開いているSQLファイルのエディター上で右クリックし、**Generate Doma Result Class...**を選びます。
3. **Connection profile**で登録済みの接続を選ぶか、JDBCドライバーのJAR、ドライバークラス、接続URL、ユーザー名・パスワードを入力します。依存JARがある場合は複数指定できます。
4. 出力先のソースフォルダー、パッケージ、クラス名を指定します。名前から型を指定する場合は**Type mappings (optional)**で設定ファイルを選びます。プロジェクト直下に`name.properties`があれば初期選択されます。
5. **Metadata SQL**でテンプレートを確認し、**Preview**を押します。ダミー引数は自動で用意され、Doma本体がSQLを組み立てます。DTO・定数を参照する場合は、先にプロジェクトをビルドしてください。
6. 既存ファイルがあれば**Merge／Overwrite／Cancel**から選びます。**Proposed Java**と差分を確認後、新規クラスは**Generate**、既存クラスは選択した**Merge**または**Overwrite**を押して反映します。

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

<a id="table-generation"></a>

## テーブルからDAO・Entity・SQLを生成

1. ExplorerでJavaプロジェクト、ソースフォルダー、またはその中のファイルを選択し、右クリックで**Generate Doma DAO, Entity and SQL...**を選びます。**Project**メニューからも起動できます。
2. 登録済みの**Connection profile**を選ぶか、JDBC接続情報を入力します。SELECT結果生成と同じ接続プロファイル・任意の暗号化パスワード保存・未登録のプロジェクト設定・タイムアウトを利用できます。入力済みの接続は**Save as...**で再入力せず登録でき、共有プロファイルの変更は**Update profile**で保存します。
3. Javaの出力先、SQLのリソースフォルダー、Entity／DAOのパッケージを指定します。EntityListenerの生成、`name.properties`、既存のdoma-genのFreeMarkerテンプレートディレクトリーも指定できます。これらの生成設定は接続情報とは別にプロジェクトごとに保持し、Cancelで閉じても復元します。
4. **Tables → Load tables**で一覧を読み込み、対象テーブルにチェックを入れます。スキーマはJDBCのパターンで絞り込みでき、空欄はDBの既定値です。Viewの表示、文字列フィルター、表示中の全選択も可能です。フィルターで隠れたテーブルのチェックは保持し、**Clear selection**で全解除します。
5. **Preview**で生成し、既存ファイルがあれば**Merge／Overwrite／Cancel**を選択します。**Proposed files**でファイルごとに選び直すことや、全既存ファイルへの一括指定ができます。**Keep unchanged**は既存ファイルを変更しません。
6. **Compare all changes...**で全ファイルの差分ツリーを確認し、**Generate files**で保存します。更新方式を変更したら再度差分確認が必要です。PreviewとCancelでは生成ファイルを書き込みません。未保存の編集、Preview後の変更、マージ競合があれば一括保存を止めます。

Gradle／Antの`Gen`と同じ、公式doma-genの`TableMetaReader`、Entity／DAO／Listener／SQLの記述ファクトリー、FreeMarkerの`Generator`を使用します。Gradleの実行は不要です。SQLの独自解析や条件判定は追加していません。既存の`dao.ftl`・`entityListener.ftl`・`entity.ftl`・`*.sql.ftl`はそのディレクトリーを指定して利用でき、不足するテンプレートは公式の既定値に戻ります。プロジェクト固有のアノテーションやユーティリティーを参照するテンプレートでは、出力先にその依存関係が必要です。既定のEntityはprivateフィールドとpublicのアクセサー、importした型を出力します。独自の`entity.ftl`を指定した場合は、その内容に従います。

主キー・自動採番・Version列・型変換はdoma-genに従います。主キーがあれば`selectById.sql`、Version列もあれば`selectByIdAndVersion.sql`を生成します。主キーがないテーブルは既定のSELECT SQLを生成しません。SQLの出力先は`META-INF/DAOの完全修飾名をフォルダーにしたパス/`です。**Include schema in @Table**はEntityのアノテーションに適用します。SQLは公式テンプレートどおりのため、アプリケーションの既定スキーマを合わせるか、既存のSQLテンプレートで修飾してください。異なるスキーマの同名テーブルなどで出力先が重複する場合は生成を止めます。DBメタデータを読み取り、doma-genのメタデータ確認用SELECTを実行する場合がありますが、DBのデータ更新や生成DAOのINSERT／UPDATE／DELETEは実行しません。

前回の生成結果がない既存**Java**のMergeでは、既存のクラスアノテーション・メンバー・コメントを保持し、足りないクラスアノテーション・メンバー・importを追加します。既存メンバーを生成専用とは判定しません。以降は前回の生成結果と現在／今回のJavaをメンバー単位で比較し、手動追加を保持します。同じ箇所の競合は更新を止めます。前回の生成結果がない既存**SQL**のMergeは、ファイル全体を保持します。置き換える場合は明示的にOverwriteを選びます。以降のSQLはEclipseの3方向差分でマージし、重なる変更があれば止めます。

前回の生成結果は生成後にEclipseワークスペースのメタデータ内だけに保存し、プロジェクトやGitには追加しません。別ワークスペースには引き継がれません。Overwriteは手動コードとコメントも含めてファイル全体を置き換えます。既存ファイルの文字コード・BOM・改行を維持し、Eclipseのローカルヒストリーを保持します。特に既存Javaのクラスアノテーション・型や独自テンプレートは差分を確認してください。今回の生成対象にない古いファイルの削除は行いません。

### FTLテンプレートの編集

テーブル生成画面の**Configuration → Edit templates...**で、選択中のテンプレートディレクトリー内のFTLを編集できます。ディレクトリーが未指定なら、プロジェクト直下の`doma-gen-templates`を開きます。**Copy missing defaults**または**New...**を押すまで、フォルダーやファイルは作成しません。

1. **Copy missing defaults**で、`entity.ftl`・`dao.ftl`・`entityListener.ftl`・`selectById.sql.ftl`・`selectByIdAndVersion.sql.ftl`・`lib.ftl`を編集用にコピーします。既存ファイルは空ファイルも含めて上書きしません。Entityのコピーは、プラグインの既定値と同じprivateフィールドです。
2. 一覧からファイルを選んで編集し、**Save**でUTF-8保存します。構文はFreeMarker本体で検査します。未保存の編集がある状態で切り替えたり閉じたりすると**Save／Discard／Cancel**を選択できます。**New...**で`selectAll.sql.ftl`などを追加でき、**Reload**で外部からの変更を読み直せます。
3. 編集画面を閉じて、もう一度**Preview**を押します。編集画面を開くと前回の生成プレビューは無効になり、Previewごとに保存済みのテンプレートを読み直します。評価エラーはFTLのファイル名と行番号を表示します。使用できる変数・プロパティは公式doma-genに従い、標準テンプレート先頭のコメントにデータモデルのクラス名があります。
4. 生成ファイルとマージ／上書きの選択を確認し、差分比較後に生成します。

`lib.ftl`は公式テンプレートから読み込まれる共通ファイルで、作成者・著作権表記などに使えます。サブフォルダー内の`.ftl`も一覧に表示します。別のEclipseテキストエディターに未保存の編集がある場合や、読み込み後にディスク上のファイルが変わった場合は、そのまま上書きしません。別のエディターで保存／編集を戻し、Reloadしてください。明示的な**Save／New／Copy**による変更は、生成画面を後でCancelしても残ります。FTLの編集にDB接続は不要です。このFTL設定はテーブルからのDAO・Entity・SQL生成に適用します。

## 既存クラスの差分プレビューと更新

既存クラスと同じソースフォルダー・パッケージ・クラス名を指定して**Preview**を押すと、DB接続前に**Merge／Overwrite／Cancel**を表示します。Previewごとに選択し、初期選択はMergeです。新規ファイルではこの選択は表示しません。

- **Merge（マージ）**：既存クラスを基に、生成対象の変更を反映します。互換性のある既存フィールド、手動メソッド、コンストラクター、コメントを保持します。
- **Overwrite（上書き）**：ファイル全体を新しく生成したクラスに置き換えます。手動メソッド、コンストラクター、コメント、他のトップレベル型も削除されます。管理タグのない型の不一致などでマージできない場合にも選択できます。
- **Cancel**または選択画面を閉じる：Previewを中止します。DB接続やJavaファイルへの書き込みは行いません。

選択後、変更前の**Current saved class**と、変更後の**Proposed merge**または**Proposed overwrite**を並べた比較画面を開きます。比較画面は読み取り専用です。確認して閉じ、生成ダイアログの**Merge**または**Overwrite**を押して初めて保存します。**Compare changes...**から再確認でき、**Proposed Java**では更新後の全体を確認できます。比較画面を閉じただけの場合や生成ダイアログをキャンセルした場合は保存しません。変更がなければ反映ボタンは無効になります。

以下の保持・管理タグの仕様は、**Merge**を選択した場合のものです。

生成するフィールド・getter/setterには、管理対象を示すJavadocタグ`@domaGenerated`を付けます。更新時はJavaの構文情報とソース上の位置を使って、必要な箇所を変更します。

- SELECT列の追加に応じてフィールドとアクセサーを追加します。型変更ではフィールドと単純なgetter/setterの型を更新します。`name.properties`の指定も反映します。
- 手動で追加したメソッド、コンストラクター、フィールド、import、クラスのアノテーション、内部クラスなどを保持します。手動で変更したアクセサーの本体も、型の整合性を保てる場合は保持します。
- 管理タグがない既存フィールドでも、Java型とDoma上の列対応が一致すれば、そのまま再利用します。フィールド・アクセサーの書き換えや管理タグの追加は行わず、新しい列だけを追加できます。
- Javadoc・行コメント・ブロックコメントを保持します。不要になった生成メンバーを削除する場合も、その中のユーザーコメントは独立したコメントとして残します。管理用タグは削除対象の宣言とともに除きます。
- 削除する列のフィールドを手動コードが参照している場合、手動変更されたアクセサーの型変更が必要な場合、手動メンバーと衝突する場合などは、理由を表示して更新を止めます。
- 更新案を保存しないJDTの作業コピーで検査します。対象プロジェクトのビルドパスを使い、手動メソッドを含む型エラーやJavaエラーがあれば更新を止めます。
- ワイルドカードimportの型は対象プロジェクトから解決します。同名の型が複数あって曖昧な場合は更新を止め、明示的なimportまたは完全修飾名を求めます。生成メンバーで使っていた通常のimportは、コード・Javadocのどこからも参照されなくなった場合に削除します。それ以外の手動importやstatic・ワイルドカードimportは保持します。

UTF-8 BOM付きの既存クラスも更新でき、BOMは保存時に維持します。

既存ファイルの編集は、プレビュー前に保存してください。**Merge・Overwriteのどちらでも**、プレビュー後にファイルが変更された場合やエディターに未保存の編集がある場合は保存せず、Previewのやり直しを求めます。上書きでもJavaの検査を行い、既存ファイルの文字コード・BOM・改行コードを維持します。更新前の内容はEclipseのローカル履歴を保持する指定で保存します。履歴の保持期間などはワークスペースの設定に従います。比較・検査の対象はそのJavaファイルであり、他ファイルの呼び出し元の修正は別途必要になることがあります。

### 旧版で生成したクラス

元の`Generated from SELECT result metadata.`というクラスコメントがあり、管理タグがまだない場合は、現在のSQLと一致する単純な`@Column`付きフィールドと通常のアクセサーを引き継ぎます。コメントや手動メソッドは残し、引き継いだメンバーに管理タグを追加します。

現在のSQLにない旧フィールドは、生成元を確定できないため残してプレビューの案内に表示します。これらの項目は手動で確認してください。管理タグのないフィールドの型・列対応が異なる場合や、immutableエンティティ、特殊な宣言は自動更新せず、調整を求めます。

### 管理タグのない既存エンティティ

`@Entity`の既存クラスに`private String code;`があり、SELECT結果が文字列型の`CODE`列なら、`@Column`や`@domaGenerated`を追加せず既存フィールドを利用できます。列名の大文字・小文字はDomaの結果マッピングと同様に区別しません。明示された`@Column(name = "...")`と、クラスの`@Entity(naming = NamingType....)`も判定に使います。命名規則が未指定の場合は標準のNONEを使います。

Mergeではコンストラクター、`equals`・`hashCode`・`toString`、コメント、SELECTにない手動フィールドを保持します。すべて一致すれば変更なしとなり、Mergeは無効になります。新しい列があれば、その生成メンバーを差分表示します。

既存フィールドを再利用しても、後から自動で型変更・削除する管理対象にはしません。型や列対応が異なる場合、static・final・transientやDomaの除外対象フィールドの場合は、理由を表示して更新を止めます。独自の`Config.getNaming()`や`@Column`名に指定した定数式はこのソース判定では実行・評価しません。

## 対応範囲

- 特定DBに固定せず、公式doma-genがSELECT結果の`ResultSet.getMetaData()`で列ラベル・型を取得します。
- JOIN・集計・CTEの結果に対応します。空の検索結果でもメタデータを取得できます。
- 公式doma-genと同様にSELECTを実行して結果の列情報を取得します。結果行を順番に取得する処理は呼びません。
- 列名をキャメルケースに変換し、別名を`@Column`に保持します。Javaの予約語やフィールド名の衝突にも対応します。
- NULLに対応できるラッパー型、BigDecimal、String、byte[]、LocalDate/LocalTime/LocalDateTime、LOB型などを生成します。
- 詳細な型対応表は[英語README](README.md#type-mapping)を参照してください。

### Doma本体で処理してから公式doma-genで生成

**Doma 2.57.0の`SqlParser`・`ExpressionEvaluator`・`NodePreparedSqlBuilder`でテンプレートを処理し、doma-gen 2.28.0の`ResultSetMetaReader.read(sql)`へ渡します。** SQLの解析、条件判定、ループ、埋め込み変数、不要な`AND`・`OR`の除去は、変更していないDoma本体の処理です。独自のSQLパーサー、式評価器、分岐選択・SQL補正、失敗時の生SQLへの切り替えは追加しません。

引数の入力画面はありません。プラグインはDomaの解析済みノードから必要な引数名を取得し、`META-INF/<DAOの完全修飾名>/<メソッド名>.sql`に対応するDAOの宣言型、ジェネリック型、SQL中のテストリテラルを手掛かりにダミー引数を自動作成します。数値・文字列・真偽値・日時・配列・要素1件のコレクション、引数なしコンストラクターを持つDTO、recordなどに対応します。DTOの基本フィールドとSQLが参照する入れ子のDTOにも値を設定します。実際の検索条件や一致するデータは不要で、0件の結果でも列情報を取得できます。

DTO・定数・アプリケーションのメソッドは、プロジェクトと参照先のビルド済みクラス・依存JARから自動で読み込みます。追加のクラスパス入力は不要です。DTO型を特定できない場合や必要なコンストラクター・クラスがない場合は、プロジェクトのビルド・DAO宣言・依存関係を確認するエラーを表示します。架空のDTOや代替メソッドの結果は作成しません。基本型だけの独立したSQLでは、テストリテラルや式の型から引数を用意できます。

```sql
SELECT e.id, e.name FROM employee e WHERE AND e.id = /*id*/1
```

この例では、Domaが先頭の`AND`を除去し、`e.id = ?`を組み立てます。バインド値の設定もDoma標準の`PreparedSqlParameterBinder`が行います。元SQLファイルも、表示中のMetadata SQLも自動では書き換えません。`%expand`はDomaの拡張フックへ列情報取得用の`*`を渡し、別名の評価・付与はDomaに任せます。`/*%expand "e"*/*`は`e.*`となり、DBが返す実際の列を取得します。

JDBC接続、**Metadata SQL**のテンプレート、出力先のPackage・Class nameを指定してPreviewします。公式リーダーは**`executeQuery()`でSELECTを実行し、結果セットの`getMetaData()`で列情報を読み取ります。** 結果行を順番に取得する処理は呼びませんが、DBではSELECTが実行されます。既存のキャンセル・タイムアウト、接続プロファイル、差分比較・手動コード保持は継続します。

ダミー値で選ばれた1つの経路から生成します。引数がWHEREの絞り込みだけに使われる場合、通常は列名・型に影響しません。SELECT列・別名・型・JOIN先などが引数で変わるSQLでは、すべての経路を表すクラスになる保証はありません。**Proposed Javaで列構成を確認してください。** プロジェクトのメソッドは実際に呼ばれるため、ダミー値でも評価可能である必要があります。

入力チェックは更新を含まない単一SELECTの確認を維持します。Domaが組み立てたSQLも実行前に確認します。SQL syntaxはこのチェックの引用・コメントの規則とDB方言を選びますが、Doma標準パーサーが受け付けない構文を独自に補正する設定ではありません。SQL ServerのCTEはラッパーや`SET FMTONLY`を挿入せず実行します。Domaのエラーコード・行番号、JDBCのコード・段階をSQL本文や引数値を含めず表示します。

JDBC型からJava型への変換も公式の`GenDialect`を使います。既存のname.properties、privateフィールド、import、手動コード保持は継続します。ライブラリは更新サイトに同梱し、Ant・Gradleの別途インストールは不要です。元のSQLファイルは変更しません。

SQLStateだけではOracleエラーの原因を特定できないことがあります。**Error details...**でORA番号・ベンダー番号・失敗した段階を確認してください。同じSQLがSQLクライアントで実行できる場合は、JDBCドライバーや接続セッションの現在のスキーマなどの違いを切り分けます。不具合報告にはコードと段階のみを使い、接続情報やパスワードを含めないでください。

既存クラスのマージ・Java検査のエラーは、それぞれの段階を表示します。JDBCドライバーの解放エラーとは区別します。

JDBCのOTHER・ARRAY型やタイムゾーン付き日時、DB独自型に既定のマッピングがない場合は、名前による型指定またはSQLのCASTを検討してください。独自ドメイン型の自動推定は行いませんが、設定ファイルで明示指定できます。同じ列名が複数ある場合はASで区別します。Domaの分岐間でSELECT列の名前・順序・型が異なるSQLは、生成対象の列構成を明示する必要があります。

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

現在は**doma-gen 2.28.0の`EntityDescFactory`・`EntityPropertyDescFactory`・`Generator`を直接呼び出します**。privateフィールド、`@domaGenerated`管理タグ、型名の衝突対策にはEclipse向けの出力テンプレートとアダプターを使います。SQL結果の列情報取得は**公式の`ResultSetMetaReader`**、型の変換は**`GenDialect`**を直接使用します。

Eclipseの接続プロファイル、プレビュー、既存クラスへの差分更新はプラグインが担当します。公式ライブラリと依存ライブラリは更新サイトに同梱し、GradleやAntコマンドの別途インストールは不要です。Antタスクとしてdoma-gen全体を起動する方式ではありません。

ダミー引数は自動で用意し、Doma本体でSQLを組み立ててから公式`ResultSetMetaReader`へ渡します。Doma標準の型付きバインド処理を使い、SELECTを実行して列情報を取得します。型マッピング・名前規則と手動コード保持の仕様も維持します。詳細と参照ソースは[英語README](README.md#relationship-to-the-official-generator)、再配布ライブラリは[THIRD_PARTY_NOTICES](plugin/THIRD_PARTY_NOTICES)に記載しています。

## オープンソースとしての公開

ソースの公開先は[vipt/doma-result-generator](https://github.com/vipt/doma-result-generator)、案内ページ兼Eclipse更新サイトは[GitHub Pages](https://vipt.github.io/doma-result-generator/)です。Apache License 2.0で配布します。初期プレビュー版として、確認済みの範囲と未検証の項目を導入ページに記載しています。

GitHub Actionsの **Build and deploy Pages** が、`main`へのpush時にビルド・テストを実行し、成功した場合に案内ページと更新サイトを公開します。Pull Requestではテストだけを実行します。GitHubの **Settings → Pages → Source** は **GitHub Actions** を使います。テスト結果と更新サイトZIPもActionsの成果物に保存します。GitHub Releasesの作成は別途行います。

初回公開前には[CONTRIBUTING.md](CONTRIBUTING.md)のEclipse実機確認と、公開時に対応を表明するDB・ドライバーの組み合わせで確認してください。今後は、接続設定の再利用、Domaの動的SQLの展開などを拡張できます。
