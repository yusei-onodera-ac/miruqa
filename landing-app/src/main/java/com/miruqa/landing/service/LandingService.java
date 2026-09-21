package com.miruqa.landing.service;

import com.miruqa.landing.entity.*;
import com.miruqa.landing.repository.ContentRepository;
import java.util.List;

/** ランディングページに表示する内容をまとめる。 */
public class LandingService {
    private final ContentRepository content;

    public LandingService(ContentRepository content) { this.content = content; }

    public record Page(List<Feature> features, List<PainPoint> painPoints, List<Faq> faqs, List<MeasuredResult> results) {}

    public Page page() {
        return new Page(content.features(), content.painPoints(), content.faqs(), content.measuredResults());
    }
}
