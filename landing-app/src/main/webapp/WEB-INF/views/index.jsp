<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ja">
<head>
<title>MiruQA｜仕様書から、UIテストを作って、実行する。</title>
<meta name="description" content="仕様書を読み、実際のブラウザで画面を操作する。UIテスト（E2E）を、テスト項目書の作成から、実施、不具合票まで。2026年10月末 提供開始予定。">
<meta property="og:title" content="MiruQA｜仕様書から、UIテストを作って、実行する。">
<meta property="og:description" content="仕様書を読み、実際のブラウザで画面を操作する。UIテスト（E2E）を、テスト項目書の作成から、実施、不具合票まで。">
<meta property="og:type" content="website">
<link rel="canonical" href="https://miruqa.com/">
<%@ include file="_head.jspf" %>
</head>
<body>
<a class="skip" href="#main">本文へ移動</a>
<%@ include file="_header.jspf" %>

<main id="main">

<!-- ヒーロー -->
<section class="hero">
  <div class="wrap hero-grid">
    <div class="hero-copy">
      <p class="label">UIテスト・E2Eテスト　／　2026年10月末 提供開始予定</p>
      <h1><span class="nb">AIが画面を操作し、</span><span class="nb">テストを実施する。</span></h1>
      <p class="lead">実際のブラウザで画面を操作し、テスト項目書の作成から、実施、証拠つきの不具合票まで行います。人間のテスト担当と同じ手順を、そのまま自動にします。<b>仕様書があれば読み込み、なくても点検できます。</b></p>
      <div class="cta-row">
        <c:choose>
          <c:when test="${contactEnabled}"><a class="btn btn-primary" href="${pageContext.request.contextPath}/contact?category=EARLY_ACCESS">先行案内を希望する</a></c:when>
          <c:otherwise><span class="btn btn-primary is-disabled" aria-disabled="true">先行案内は、準備中です</span></c:otherwise>
        </c:choose>
        <a class="btn btn-line" href="#flow">使い方を見る</a>
      </div>
      <ul class="facts">
        <li>仕様書は、なくても点検できる</li>
        <li>実施は、承認した項目だけ</li>
        <li>判定は、コードで確かめた根拠つき</li>
        <li>再現手順とスクリーンショットを添付</li>
      </ul>
    </div>

    <figure class="hero-shot" aria-label="実行結果の画面イメージ">
      <div class="app">
        <div class="app-side">
          <p class="app-brand">MiruQA</p>
          <ul>
            <li>ダッシュボード</li>
            <li>プロジェクト</li>
            <li class="on">点検の実行</li>
            <li>不具合</li>
            <li>履歴</li>
          </ul>
        </div>
        <div class="app-main">
          <div class="app-top"><b>実行結果</b><span>デモサイト ／ 仕様項目 20件</span></div>
          <div class="app-kpi">
            <div><span>テスト項目</span><b>33</b></div>
            <div><span>不合格</span><b class="bad">13</b></div>
            <div><span>要確認</span><b class="rev">8</b></div>
            <div><span>仕様の確認</span><b>16 / 20</b></div>
          </div>
          <table class="app-table">
            <thead><tr><th>状態</th><th>項目</th><th>観点</th></tr></thead>
            <tbody>
              <tr><td><i class="sq bad"></i>不合格</td><td>注文確定の連打で、注文が複数件できる</td><td>セキュリティ</td></tr>
              <tr><td><i class="sq bad"></i>不合格</td><td>送料が、特商法の表記と決済画面で一致しない</td><td>表示・文言</td></tr>
              <tr><td><i class="sq rev"></i>要確認</td><td>「お問い合わせ」の表記ゆれ</td><td>表示・文言</td></tr>
              <tr><td><i class="sq ok"></i>合格</td><td>カートの数量変更が、合計に反映される</td><td>機能</td></tr>
              <tr><td><i class="sq ok"></i>合格</td><td>必須項目の未入力で、エラーが表示される</td><td>入力検証</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <figcaption>画面イメージ。記録済みのデモ実行（自作のデモサイトに、意図的に不具合を仕込んだもの）の結果に基づく。</figcaption>
    </figure>
  </div>
</section>

<!-- 課題 -->
<section class="section band" id="problems">
  <div class="wrap">
    <div class="head"><p class="eyebrow">課題</p><h2>こんなお悩み、ありませんか？</h2></div>
    <div class="cols3">
      <c:forEach var="p" items="${page.painPoints()}" varStatus="st">
        <article class="col reveal">
          <span class="no">0${st.count}</span>
          <h3><c:out value="${p.title()}"/></h3>
          <p><c:out value="${p.solution()}"/></p>
        </article>
      </c:forEach>
    </div>
  </div>
</section>

<!-- 機能 -->
<section class="section" id="features">
  <div class="wrap">
    <div class="head"><p class="eyebrow">機能</p><h2>仕様書を渡すと、テストの一式が返る。</h2></div>

    <c:forEach var="f" items="${page.features()}" varStatus="st">
      <article class="row reveal ${st.index % 2 == 1 ? 'flip' : ''}">
        <div class="row-text">
          <span class="no">0${st.count}</span>
          <h3><c:out value="${f.title()}"/></h3>
          <p><c:out value="${f.body()}"/></p>
        </div>
        <div class="row-art" aria-hidden="true">
          <c:choose>
            <c:when test="${f.code() == 'spec'}">
              <div class="snip">
                <p class="snip-h">仕様書 → テスト項目書</p>
                <table class="app-table">
                  <thead><tr><th>仕様</th><th>生成された項目</th></tr></thead>
                  <tbody>
                    <tr><td>送料は全国一律800円</td><td>決済画面の送料が800円であることを確認</td></tr>
                    <tr><td>注文は1回のみ確定</td><td>確定ボタンの連打で、注文が1件のままか確認</td></tr>
                    <tr><td>メールは必須入力</td><td>空欄で送信し、エラーが表示されるか確認</td></tr>
                  </tbody>
                </table>
                <p class="snip-f">＋ 文章で、項目を追加（追加案を確認してから採用）</p>
              </div>
            </c:when>
            <c:when test="${f.code() == 'browser'}">
              <div class="snip">
                <p class="snip-h">実行ログ</p>
                <ol class="log">
                  <li><b>1</b>商品ページを開く</li>
                  <li><b>2</b>「カートに入れる」を押す</li>
                  <li><b>3</b>カートで数量を「2」にする</li>
                  <li><b>4</b>決済画面へ進み、合計を確認する</li>
                  <li class="last"><b>5</b>期待する合計と、表示を比べる</li>
                </ol>
              </div>
            </c:when>
            <c:when test="${f.code() == 'evidence'}">
              <div class="snip">
                <p class="snip-h">不具合票</p>
                <dl class="kv">
                  <div><dt>状態</dt><dd><i class="sq bad"></i>不合格（確定）</dd></div>
                  <div><dt>内容</dt><dd>連打（5回・100ms間隔）で、注文が5件作成された</dd></div>
                  <div><dt>根拠</dt><dd>テスト環境の状態確認：注文数 0 → 5</dd></div>
                  <div><dt>添付</dt><dd>再現手順 ／ スクリーンショット</dd></div>
                </dl>
              </div>
            </c:when>
            <c:otherwise>
              <div class="snip">
                <p class="snip-h">実行の承認</p>
                <table class="app-table">
                  <thead><tr><th>項目</th><th>種別</th><th>実行</th></tr></thead>
                  <tbody>
                    <tr><td>カートの数量変更</td><td>通常</td><td><i class="sq ok"></i>実施</td></tr>
                    <tr><td>注文確定の連打（上限 10回）</td><td>要承認</td><td><i class="sq rev"></i>承認待ち</td></tr>
                    <tr><td>価格の改ざん</td><td>要承認</td><td><i class="sq rev"></i>承認待ち</td></tr>
                  </tbody>
                </table>
                <p class="snip-f">テスト環境の宣言と、項目ごとの承認がそろったときだけ実行</p>
              </div>
            </c:otherwise>
          </c:choose>
        </div>
      </article>
    </c:forEach>
  </div>
</section>

<!-- 使い方 -->
<section class="section band" id="flow">
  <div class="wrap">
    <div class="head"><p class="eyebrow">使い方</p><h2>3ステップで、点検が終わる。</h2></div>
    <ol class="steps">
      <li class="reveal"><span class="no">01</span><h3>渡す</h3><p>点検するURLを登録します。仕様書（txt・md・docx・PDF）は、あれば添付します。なくても、点検できます。</p></li>
      <li class="reveal"><span class="no">02</span><h3>確認する</h3><p>作成されたテスト項目書を見て、実施する項目を承認します。</p></li>
      <li class="reveal"><span class="no">03</span><h3>受け取る</h3><p>不具合票と、カバレッジ（仕様のどこまで確かめたか）を受け取ります。</p></li>
    </ol>
    <table class="spec reveal">
      <caption>レポートに含まれるもの</caption>
      <tr><th>仕様項目の確認状況</th><td>合格・不合格・未確認と、その理由。仕様のどこまで確かめたか。</td></tr>
      <tr><th>不具合票</th><td>重大度、再現手順、スクリーンショット、仕様の該当箇所。確定と要確認は、区別して表示。</td></tr>
      <tr><th>観点ごとの件数</th><td>機能・画面遷移・入力・表示・リンク・アクセシビリティ・使いやすさ・セキュリティ・速度・レスポンシブ。</td></tr>
      <tr><th>書き出しと再実行</th><td>CSV・HTMLレポート。承認済みの項目書は、そのまま再実行できます。</td></tr>
    </table>
    <p class="fine">使いやすさ・アクセシビリティは、現時点では、AIによるチェックリスト判定です。準拠を保証するものではありません。</p>
  </div>
</section>

<!-- 対象 -->
<section class="section" id="modes">
  <div class="wrap">
    <div class="head"><p class="eyebrow">対象</p><h2>点検の対象は、2種類。</h2></div>
    <table class="spec modes reveal">
      <thead><tr><th></th><th>開発環境の点検</th><th>公開ページの点検</th></tr></thead>
      <tbody>
        <tr><th>対象</th><td>手元の開発環境、自分のステージング環境</td><td>公開されているページ</td></tr>
        <tr><th>できること</th><td>項目書の実施、探索的テスト、連打・改ざんなどの能動テスト（承認つき）</td><td>表示・文言・リンク・アクセシビリティの点検、仕様書との照合（閲覧のみ）</td></tr>
        <tr><th>守るしくみ</th><td>テスト環境であることの宣言。公開ステージングは、ドメインの所有確認も</td><td>送信・ログイン・能動テストは、しくみとして実行できない。robots.txtを守り、ゆっくり閲覧</td></tr>
      </tbody>
    </table>
    <p class="fine">本番サイトへの能動的なテストは、できない設計です。</p>
  </div>
</section>

<!-- 安全性 -->
<section class="section band" id="safety">
  <div class="wrap">
    <div class="head"><p class="eyebrow">安全性</p><h2>画面を操作させる不安に、しくみで答える。</h2></div>
    <div class="safe-grid">
      <div class="safe reveal"><h3>操作を封じる</h3><p>危険な操作は、承認した範囲だけ。公開ページでは、送信・ログイン・能動テストを実行できません。回数と間隔にも上限があります。</p></div>
      <div class="safe reveal"><h3>データを守る</h3><p>AIに送る前に、個人情報らしい文字列をマスクします。スクリーンショットは、AIに送りません。</p></div>
      <div class="safe reveal"><h3>すべて記録する</h3><p>組織ごとにデータを分離し、操作は監査ログに記録します。AIの呼び出しの費用も、台帳に残ります。</p></div>
      <div class="safe reveal"><h3>いつでも止める</h3><p>実行中でも、緊急停止できます。実行・日・組織ごとの費用の上限を超えると、AIの呼び出しを止めます。</p></div>
    </div>
    <p class="fine">上流のAI事業者でのデータの扱いは、確認中です。実在の個人情報は、テスト環境に置かないでください。</p>
  </div>
</section>

<!-- 実測 -->
<section class="section" id="results">
  <div class="wrap">
    <div class="head"><p class="eyebrow">検証</p><h2>自作のデモサイトでの実測。</h2></div>
    <div class="kpi-band">
      <c:forEach var="r" items="${page.results()}">
        <div class="kpi reveal"><b><c:out value="${r.value()}"/></b><span><c:out value="${r.label()}"/></span></div>
      </c:forEach>
    </div>
    <p class="fine">自作のデモサイトに、意図的に14件の不具合を仕込んで測定しました。実行のたびにAIの進め方が変わるため、結果には幅があります。実サイトでの結果を保証するものではありません。</p>
  </div>
</section>

<!-- FAQ -->
<section class="section band" id="faq">
  <div class="wrap narrow">
    <div class="head"><p class="eyebrow">FAQ</p><h2>よくあるご質問</h2></div>
    <c:forEach var="q" items="${page.faqs()}">
      <details class="faq">
        <summary><c:out value="${q.question()}"/></summary>
        <p><c:out value="${q.answer()}"/></p>
      </details>
    </c:forEach>
  </div>
</section>

<!-- CTA -->
<section class="cta-band" id="contact">
  <div class="wrap cta-inner">
    <c:choose>
      <c:when test="${contactEnabled}">
        <div>
          <h2>公開の前に、テストを任せてみませんか。</h2>
          <p>2026年10月末の提供開始に向けて、先行案内と、導入のご相談を受け付けています。</p>
        </div>
        <div class="cta-actions">
          <a class="btn btn-white" href="${pageContext.request.contextPath}/contact?category=EARLY_ACCESS">先行案内を希望する</a>
          <a class="btn btn-outline" href="${pageContext.request.contextPath}/contact?category=DEMO">導入のご相談</a>
        </div>
      </c:when>
      <c:otherwise>
        <div>
          <h2>公開の前に、テストを任せてみませんか。</h2>
          <p>2026年10月末の提供開始に向けて、準備を進めています。先行案内の受付は、まもなく始まります。</p>
        </div>
      </c:otherwise>
    </c:choose>
  </div>
</section>

</main>
<%@ include file="_footer.jspf" %>
</body>
</html>
