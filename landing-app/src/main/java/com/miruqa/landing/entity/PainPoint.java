package com.miruqa.landing.entity;

/** 「こんなお悩みはありませんか？」の1件。悩みの題名と、MiruQAでの解決の説明。 */
public record PainPoint(String title, String solution, String illustrationKey) {}
