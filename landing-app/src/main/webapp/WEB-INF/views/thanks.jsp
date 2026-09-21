<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ja">
<head>
<title>送信しました｜MiruQA</title>
<meta name="robots" content="noindex">
<%@ include file="_head.jspf" %>
</head>
<body class="page-plain">

<%@ include file="_header.jspf" %>
<main id="main" class="wrap narrow form-page">
  <p class="eyebrow">THANK YOU</p>
  <h1 class="h1-sm">送信しました。</h1>
  <p>お問い合わせを受け付けました。内容を確認して、ご連絡します。</p>
  <c:if test="${not empty ref}">
    <p class="refbox">受付番号　<b><c:out value="${ref}"/></b></p>
  </c:if>
  <p><a class="btn btn-primary" href="${pageContext.request.contextPath}/">トップへ戻る</a></p>
</main>
<%@ include file="_footer.jspf" %>
</body>
</html>
