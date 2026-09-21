package com.miruqa.landing.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;

/** エラーページ（web.xmlのerror-pageから呼ばれる）。元のステータスコードを保ったまま、表示する。 */
@WebServlet("/error")
public class ErrorServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Object code = req.getAttribute("jakarta.servlet.error.status_code");
        resp.setStatus(code instanceof Integer i ? i : 500);
        req.getRequestDispatcher("/WEB-INF/views/error.jsp").forward(req, resp);
    }
}
