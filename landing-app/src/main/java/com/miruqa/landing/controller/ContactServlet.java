package com.miruqa.landing.controller;

import com.miruqa.landing.config.AppContextListener;
import com.miruqa.landing.service.InquiryService;
import com.miruqa.landing.value.InquiryCategory;
import com.miruqa.landing.value.InvalidValueException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** お問い合わせ（GET /contact = 入力画面、POST /contact = 送信）。送信後は、リダイレクト（PRG）で二重送信を防ぐ。 */
@WebServlet("/contact")
public class ContactServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!com.miruqa.landing.config.AppConfig.contactEnabled()) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        var s = AppContextListener.services(getServletContext());
        String category = req.getParameter("category");
        req.setAttribute("form", new Form("", "", "", category == null ? "EARLY_ACCESS" : category, "", false));
        show(req, resp, s, null);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!com.miruqa.landing.config.AppConfig.contactEnabled()) { resp.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
        var s = AppContextListener.services(getServletContext());
        Form form = new Form(p(req, "name"), p(req, "company"), p(req, "email"), p(req, "category"), p(req, "message"),
                "on".equals(req.getParameter("consent")));
        // ボット対策: 見えない入力欄に値があれば、成功したように見せて、保存しない
        if (!p(req, "website").isEmpty()) { resp.sendRedirect(req.getContextPath() + "/contact/thanks?ref=MQ-00000000"); return; }
        if (!s.csrf().verify(req.getParameter("csrf"))) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            req.setAttribute("form", form);
            show(req, resp, s, "ページの有効期限が切れました。もう一度、送信してください。");
            return;
        }
        try {
            var receipt = s.inquiry().submit(new InquiryService.Request(form.name, form.company, form.email, form.category,
                    form.message, form.consent, ClientAddress.of(req)));
            resp.sendRedirect(req.getContextPath() + "/contact/thanks?ref=" + URLEncoder.encode(receipt.referenceCode(), StandardCharsets.UTF_8));
        } catch (InvalidValueException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            req.setAttribute("form", form);
            show(req, resp, s, e.getMessage());
        } catch (InquiryService.RateLimitedException e) {
            resp.setStatus(429);
            req.setAttribute("form", form);
            show(req, resp, s, e.getMessage());
        }
    }

    private void show(HttpServletRequest req, HttpServletResponse resp, AppContextListener.Services s, String error) throws ServletException, IOException {
        req.setAttribute("csrf", s.csrf().issue());
        req.setAttribute("error", error);
        req.setAttribute("categories", InquiryCategory.values());
        req.getRequestDispatcher("/WEB-INF/views/contact.jsp").forward(req, resp);
    }

    private static String p(HttpServletRequest req, String name) {
        String v = req.getParameter(name);
        return v == null ? "" : v;
    }

    /** 画面に戻す入力（値の検証は、サービス層の値オブジェクトで行う）。 */
    public record Form(String name, String company, String email, String category, String message, boolean consent) {
        public String getName() { return name; }
        public String getCompany() { return company; }
        public String getEmail() { return email; }
        public String getCategory() { return category; }
        public String getMessage() { return message; }
        public boolean isConsent() { return consent; }
    }
}
