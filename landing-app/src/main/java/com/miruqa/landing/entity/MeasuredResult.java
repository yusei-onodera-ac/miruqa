package com.miruqa.landing.entity;

/** 実測の結果（ページに載せる数字）。測っていない数字は、載せない。 */
public record MeasuredResult(String value, String label) {}
