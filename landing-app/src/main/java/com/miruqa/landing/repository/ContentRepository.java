package com.miruqa.landing.repository;

import com.miruqa.landing.entity.*;
import java.util.List;

/** ページに表示する内容の取得元。今は、コード内の固定の内容（将来、DBやCMSに差し替えられる）。 */
public interface ContentRepository {
    List<Feature> features();
    List<PainPoint> painPoints();
    List<Faq> faqs();
    List<MeasuredResult> measuredResults();
}
