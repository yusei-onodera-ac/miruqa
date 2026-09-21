<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ja">
<head>
<title>お問い合わせ｜MiruQA</title>
<meta name="robots" content="noindex">
<%@ include file="_head.jspf" %>
</head>
<body class="page-plain">

<%@ include file="_header.jspf" %>
<main id="main" class="wrap narrow form-page">
  <p class="eyebrow">CONTACT</p>
  <h1 class="h1-sm">お問い合わせ・先行案内</h1>
  <p class="sub">2026年10月末の提供開始に向けて、先行案内の登録と、導入のご相談を受け付けています。</p>

  <c:if test="${not empty error}">
    <div class="alert" role="alert"><c:out value="${error}"/></div>
  </c:if>

  <form method="post" action="${pageContext.request.contextPath}/contact" class="form" novalidate>
    <input type="hidden" name="csrf" value="<c:out value='${csrf}'/>">
    <div class="hp" aria-hidden="true"><label>Webサイト<input type="text" name="website" tabindex="-1" autocomplete="off"></label></div>

    <div class="field">
      <label for="category">種別<span class="req">必須</span></label>
      <select id="category" name="category">
        <c:forEach var="c" items="${categories}">
          <option value="${c.name()}" ${form.category == c.name() ? 'selected' : ''}><c:out value="${c.label}"/></option>
        </c:forEach>
      </select>
    </div>
    <div class="field">
      <label for="name">お名前<span class="req">必須</span></label>
      <input id="name" name="name" type="text" maxlength="60" autocomplete="name" value="<c:out value='${form.name}'/>" required>
    </div>
    <div class="field">
      <label for="company">会社名・組織名<span class="opt">任意</span></label>
      <input id="company" name="company" type="text" maxlength="100" autocomplete="organization" value="<c:out value='${form.company}'/>">
    </div>
    <div class="field">
      <label for="email">メールアドレス<span class="req">必須</span></label>
      <input id="email" name="email" type="email" maxlength="254" autocomplete="email" value="<c:out value='${form.email}'/>" required>
    </div>
    <div class="field">
      <label for="message">お問い合わせ内容<span class="req">必須</span></label>
      <textarea id="message" name="message" rows="7" maxlength="2000" required><c:out value="${form.message}"/></textarea>
      <p class="hint">2000文字まで。個人情報や機密情報は、書かないでください。</p>
    </div>
    <label class="consent"><input type="checkbox" name="consent" ${form.consent ? 'checked' : ''}> <span><a href="${pageContext.request.contextPath}/contact/privacy" target="_blank" rel="noopener">プライバシーポリシー（案）</a>に同意します。</span></label>
    <button class="btn btn-primary" type="submit">送信する</button>
    <p class="fine">送信内容は、先行案内と、お問い合わせへの対応のためにだけ使います。</p>
  </form>
</main>
<%@ include file="_footer.jspf" %>
</body>
</html>
