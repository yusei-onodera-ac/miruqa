package com.miruqa.landing.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.regex.Pattern;

/** 送信完了（GET /contact/thanks?ref=...）。受付番号は、形式を確認してから表示する。 */
@WebServlet("/contact/thanks")
public class ThanksServlet extends HttpServlet {
    private static final Pattern REF = Pattern.compile("^MQ-[A-Z0-9]{8}$");

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!com.miruqa.landing.config.AppConfig.contactEnabled()) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        String ref = req.getParameter("ref");
        req.setAttribute("ref", ref != null && REF.matcher(ref).matches() ? ref : "");
        req.getRequestDispatcher("/WEB-INF/views/thanks.jsp").forward(req, resp);
    }
}
