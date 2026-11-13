# Doma Result Generator

[インストール・更新サイト](https://vipt.github.io/doma-result-generator/) · [English](README.md) · [Apache-2.0](LICENSE)

SELECTの結果を格納するDoma 2用のJavaクラスを生成する、独立したコミュニティ製のEclipseプラグインです。JOINや集計など、1つのテーブルと対応しない検索結果に利用できます。

Daoの呼び出し元から、対応するSQLファイルへ直接移動する機能もあります。

**初期試作版です。** H2とDoma 2.57.0でコア部分をテストします。他DBへの対応はJDBCドライバーの実行前メタデータ取得機能に依存します。Doma・Eclipseの公式製品ではありません。

## 使い方

1. EclipseのJavaプロジェクト内でSQLファイルを保存します。
2. Package ExplorerまたはProject ExplorerでSQLファイルを右クリックし、**Generate Doma Result Class...**を選びます。
3. JDBCドライバーのJAR、ドライバークラス、接続URL、ユーザー名・パスワードを入力します。依存JARがある場合は複数指定できます。
4. 出力先のソースフォルダー、パッケージ、クラス名を指定します。名前から型を指定する場合は**Type mappings (optional)**で設定ファイルを選びます。プロジェクト直下に`name.properties`があれば初期選択されます。
5. **Metadata SQL**で型確認用SQLを確認し、**Preview**を押します。
6. **Proposed Java**で結果を確認します。新規クラスは**Generate**、既存クラスは差分を確認して**Update**を押します。

`@Entity`、列の別名に対応する`@Column`、フィールド、getter/setterを生成します。既存クラスは差分確認後に更新できます。DAOやプロジェクト設定は変更しないため、生成したクラスをDAOの戻り値に指定してください。

接続情報はダイアログ内だけで使用し、保存しません。ドライバーは同梱せず、利用者が別途用意します。DBエラーの表示には例外クラスとSQLStateを使い、接続情報を含む可能性があるドライバーの例外本文は表示・記録しません。

## 既存クラスの差分プレビューと更新

既存クラスと同じソースフォルダー・パッケージ・クラス名を指定して**Preview**を押すと、変更前の**Current saved class**と変更後の**Proposed update**を並べたEclipseの比較画面を開きます。比較画面は読み取り専用です。確認して比較画面を閉じ、生成ダイアログの**Update**を押すと反映します。**Compare changes...**から再確認でき、**Proposed Java**では更新後のファイル全体を確認できます。比較画面を閉じただけの場合や、生成ダイアログをキャンセルした場合は保存しません。変更がなければUpdateは無効になります。

生成するフィールド・getter/setterには、管理対象を示すJavadocタグ`@domaGenerated`を付けます。更新時はJavaの構文情報とソース上の位置を使って、必要な箇所を変更します。

- SELECT列の追加に応じてフィールドとアクセサーを追加します。型変更ではフィールドと単純なgetter/setterの型を更新します。`name.properties`の指定も反映します。
- 手動で追加したメソッド、コンストラクター、フィールド、import、クラスのアノテーション、内部クラスなどを保持します。手動で変更したアクセサーの本体も、型の整合性を保てる場合は保持します。
- Javadoc・行コメント・ブロックコメントを保持します。不要になった生成メンバーを削除する場合も、その中のユーザーコメントは独立したコメントとして残します。管理用タグは削除対象の宣言とともに除きます。
- 削除する列のフィールドを手動コードが参照している場合、手動変更されたアクセサーの型変更が必要な場合、手動メンバーと衝突する場合などは、理由を表示して更新を止めます。
- 更新案を保存しないJDTの作業コピーで検査します。対象プロジェクトのビルドパスを使い、手動メソッドを含む型エラーやJavaエラーがあれば更新を止めます。

既存ファイルの編集は、プレビュー前に保存してください。プレビュー後にファイルが変更された場合や、エディターに未保存の編集がある場合は更新せず、プレビューのやり直しを求めます。文字コードを維持して保存し、更新前の内容はEclipseのローカル履歴を保持する指定で保存します。履歴の保持期間などはワークスペースの設定に従います。比較・検査の対象はそのJavaファイルであり、公開メソッドの型変更に伴う他ファイルの呼び出し元の修正は別途必要になることがあります。

### 旧版で生成したクラス

元の`Generated from SELECT result metadata.`というクラスコメントがあり、管理タグがまだない場合は、現在のSQLと一致する単純な`@Column`付きフィールドと通常のアクセサーを引き継ぎます。コメントや手動メソッドは残し、引き継いだメンバーに管理タグを追加します。

現在のSQLにない旧フィールドは、生成元を確定できないため残してプレビューの案内に表示します。これらの項目は手動で確認してください。管理対象と識別できない同名フィールドや、immutableエンティティ、特殊な宣言は自動更新せず、調整を求めます。

## Daoの呼び出し元からSQLへ移動

Javaエディターで、次の`selectById`のメソッド名にカーソルを合わせ、右クリックして **Jump to Doma SQL** を選びます。

```java
Employee employee = employeeDao.selectById(id);
```

`employeeDao`の型が`@org.seasar.doma.Dao`付きの`com.example.dao.EmployeeDao`であれば、ソースフォルダー内の`META-INF/com/example/dao/EmployeeDao/selectById.sql`を開きます。Daoの定義からも同じコマンドを利用できます。Daoのある別のJavaプロジェクトを参照している場合にも対応します。

- `selectById-postgres.sql`などの方言別ファイルを含めて探し、複数の候補がある場合はパス付きの一覧から選択できます。
- `@Script`のメソッドでは`.script`を開きます。`@Sql`でインラインSQLを指定したメソッドは対象外です。
- ファイルがない場合は案内を表示します。ファイルの作成やDBへの接続は行いません。
- **Preferences → General → Keys** で **Jump to Doma SQL** を検索すると、好みのショートカットを設定できます。

対象は、EclipseのJavaビルドパス上のソースフォルダーにある外部SQLです。JAR内のSQLや、Daoインターフェースに宣言されていないサービスのメソッドをたどる検索は対象外です。既存の[Doma Tools](https://marketplace.eclipse.org/content/doma-tools)と併用でき、Doma Tools本体の変更は不要です。

## 対応範囲

- 特定DBに固定せず、JDBCの`PreparedStatement.getMetaData()`で列ラベル・型を取得します。
- JOIN・集計・CTEの結果に対応します。空の検索結果でもメタデータを取得できます。
- SQLの実行や検索結果の取得を行うAPIは呼び出しません。ただし、ドライバー内部では準備・解析のためにDB通信が発生する場合があります。
- 列名をキャメルケースに変換し、別名を`@Column`に保持します。Javaの予約語やフィールド名の衝突にも対応します。
- NULLに対応できるラッパー型、BigDecimal、String、byte[]、LocalDate/LocalTime/LocalDateTime、LOB型などを生成します。
- 詳細な型対応表は[英語README](README.md#type-mapping)を参照してください。

`/* employeeId */11`のようなDomaのバインドコメントとテスト値はそのまま利用できます。`/*%if*/`・`/*%for*/`・`/*%expand*/`・埋め込み変数などは、**Metadata SQL**内で具体的なSELECTに置き換えてください。この編集で元のSQLファイルは変更されません。

実行前のメタデータ取得に対応しないドライバーは初版の対象外です。JDBCのOTHER・ARRAY型やタイムゾーン付き日時、DB独自型に既定のマッピングがない場合は、名前による型指定またはSQLのCASTを検討してください。独自ドメイン型の自動推定は行いませんが、設定ファイルで明示指定できます。同じ列名が複数ある場合はASで区別します。Domaの条件によってSELECT列が変わるSQLは、生成対象の列構成を明示する必要があります。

## 名前による型指定（name.properties）

doma-genと同じ形式で、生成するJavaのプロパティ名に正規表現で型を指定できます。UTF-8のファイルを**Type mappings (optional)**から選択してください。プロジェクト直下の`name.properties`は、存在する場合に初期選択されます。別のファイル名でも使用できます。

```properties
.*Datetime$=java.time.LocalDateTime
```

たとえばSQLの列名・別名が`CREATED_DATETIME`なら、Java側の名前は`createdDatetime`になり、上のルールで型を指定できます。

```java
@org.seasar.doma.Column(name = "CREATED_DATETIME")
java.time.LocalDateTime createdDatetime;
```

- 照合対象は、キャメルケースへの変換や重複回避の接尾辞付与が済んだ**Javaのプロパティ名**です。
- 正規表現は大文字・小文字を区別し、名前全体に照合します。`createdDateTime`は`.*Datetime$`に一致しません。
- ファイルの**上から最初に一致したルールを優先**し、JDBCの既定の型を上書きします。一致しないプロパティはJDBCの型を使用します。
- `com.example.dto.EmployeeResult@.*Datetime$=java.time.LocalDateTime`のように、特定クラスだけに適用する指定もできます。クラス名部分は文字通りの名前、`@`以降は正規表現です。限定したルールは広いルールより上に置いてください。
- `$1`等のキャプチャー参照、先頭`@`によるプロパティ正規表現の指定に対応します。
- 空行、`#`・`!`で始まるコメントを使用できます。最初の`=`で区切り、前後の空白は除去します。正規表現のバックスラッシュは保持します。
- 型は完全修飾クラス名、プリミティブ型、`byte[]`を指定できます。ジェネリクス式とその他の配列表記は対象外です。不正な正規表現や型名は行番号付きで表示します。

`employeeId=com.example.domain.EmployeeId`のように、対象プロジェクトのDomaドメイン型も指定できます。指定型が存在し、Domaで利用でき、JDBCから値を取得できる必要があります。設定によって型名を変更しても、独自の値変換処理が追加されるわけではありません。NULLがあり得る場合はラッパー型を使ってください。

設定ファイルは**Preview**を押すたびに読み直します。編集後は保存してからプレビューしてください。設定欄を空にするとJDBCの既定の型だけを使用します。[設定例](examples/name.properties)も用意しています。

## ビルド・インストール

開発・実行環境はJava 17以上、Eclipseは2023-12 / 4.30以降を対象とします。ビルドにはMaven 3.8.6以上を使います。生成クラスのJava構文・日時型はJava 8に対応します。

公開版は、Eclipseの **Help → Install New Software... → Add...** で次のURLを指定するとインストールできます。

```text
https://vipt.github.io/doma-result-generator/
```

**Doma Result Generator**を選び、内容とライセンスを確認してインストール後に再起動してください。以降は **Help → Check for Updates** から更新できます。[導入ページ](https://vipt.github.io/doma-result-generator/)からZIP形式でも取得できます。

ソースからビルドする場合:

```sh
mvn --batch-mode --no-transfer-progress verify
```

`repository/target/doma-result-generator-repository-0.1.0-SNAPSHOT.zip`が更新サイトのZIPになります。Eclipseの**Help → Install New Software... → Add... → Archive...**から選び、インストール後に再起動します。対象のJavaプロジェクトではDomaの依存関係とアノテーションプロセッサーを設定しておいてください。

Eclipse本体を取得せずにコアのテストだけ実行する場合:

```sh
mvn -f tests/pom.xml test
```

## 公式生成ツールとの関係

[doma-gen](https://github.com/domaframework/doma-gen)はアーカイブ済みで、後継の[doma-codegen-plugin](https://github.com/domaframework/doma-codegen-plugin)が案内されています。Doma 2向けは後継ツールの2系です。公式にもSQLの結果から生成する機能があり、このプラグインではEclipse上でのSQL選択・接続設定・プレビュー・ファイル生成を提供します。

公式の標準型マッピングを参照し、TINYINTからShortへの変換、SQLXML型の対応を反映しました。現状は独立した実装で、公式ソースやGradle/Antの実行環境は同梱していません。公式の結果セット読み取り処理はSELECTを実行しますが、この試作版は実行前メタデータを利用します。詳細と参照ソースは[英語README](README.md#relationship-to-the-official-generator)にまとめています。

## オープンソースとしての公開

ソースの公開先は[vipt/doma-result-generator](https://github.com/vipt/doma-result-generator)、案内ページ兼Eclipse更新サイトは[GitHub Pages](https://vipt.github.io/doma-result-generator/)です。Apache License 2.0で配布します。初期プレビュー版として、確認済みの範囲と未検証の項目を導入ページに記載しています。

GitHub Actionsの **Build and deploy Pages** が、`main`へのpush時にビルド・テストを実行し、成功した場合に案内ページと更新サイトを公開します。Pull Requestではテストだけを実行します。GitHubの **Settings → Pages → Source** は **GitHub Actions** を使います。テスト結果と更新サイトZIPもActionsの成果物に保存します。GitHub Releasesの作成は別途行います。

初回公開前には[CONTRIBUTING.md](CONTRIBUTING.md)のEclipse実機確認と、公開時に対応を表明するDB・ドライバーの組み合わせで確認してください。今後は、接続設定の再利用、Domaの動的SQLの展開などを拡張できます。
