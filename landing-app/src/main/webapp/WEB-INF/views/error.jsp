<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="ja">
<head>
<title>ページが見つかりません｜MiruQA</title>
<meta name="robots" content="noindex">
<%@ include file="_head.jspf" %>
</head>
<body class="page-plain">

<%@ include file="_header.jspf" %>
<main id="main" class="wrap narrow form-page">
  <p class="eyebrow">ERROR</p>
  <h1 class="h1-sm">ページを表示できませんでした。</h1>
  <p>URLを確認するか、トップページからお進みください。</p>
  <p><a class="btn btn-primary" href="${pageContext.request.contextPath}/">トップへ戻る</a></p>
</main>
<%@ include file="_footer.jspf" %>
</body>
</html>
