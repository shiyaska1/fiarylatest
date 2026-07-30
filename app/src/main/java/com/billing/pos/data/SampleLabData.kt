package com.billing.pos.data

/** A sample test with its evaluations, seeded when a Medical-lab shop first opens the Tests screen. */
data class SampleTest(
    val name: String,
    val price: Double,
    val sampleType: String,
    val evaluations: List<SampleEval>
)

data class SampleEval(val name: String, val unit: String, val normal: String, val group: String = "")

object SampleLabData {
    val tests: List<SampleTest> = listOf(
        SampleTest(
            "Complete Blood Count (CBC)", 300.0, "Blood (EDTA)",
            listOf(
                SampleEval("Haemoglobin", "g/dL", "13.0 - 17.0", "Haematology"),
                SampleEval("Total WBC Count", "cells/cu.mm", "4000 - 11000", "Haematology"),
                SampleEval("RBC Count", "million/cu.mm", "4.5 - 5.5", "Haematology"),
                SampleEval("Platelet Count", "lakhs/cu.mm", "1.5 - 4.5", "Haematology"),
                SampleEval("PCV / Haematocrit", "%", "40 - 50", "Haematology"),
                SampleEval("Neutrophils", "%", "40 - 75", "Differential Count"),
                SampleEval("Lymphocytes", "%", "20 - 45", "Differential Count"),
                SampleEval("Eosinophils", "%", "1 - 6", "Differential Count"),
                SampleEval("Monocytes", "%", "2 - 10", "Differential Count")
            )
        ),
        SampleTest(
            "Lipid Profile", 500.0, "Serum",
            listOf(
                SampleEval("Total Cholesterol", "mg/dL", "< 200"),
                SampleEval("Triglycerides", "mg/dL", "< 150"),
                SampleEval("HDL Cholesterol", "mg/dL", "> 40"),
                SampleEval("LDL Cholesterol", "mg/dL", "< 100"),
                SampleEval("VLDL Cholesterol", "mg/dL", "7 - 35")
            )
        ),
        SampleTest(
            "Liver Function Test (LFT)", 600.0, "Serum",
            listOf(
                SampleEval("Total Bilirubin", "mg/dL", "0.3 - 1.2"),
                SampleEval("Direct Bilirubin", "mg/dL", "0.0 - 0.3"),
                SampleEval("SGOT (AST)", "U/L", "5 - 40"),
                SampleEval("SGPT (ALT)", "U/L", "5 - 40"),
                SampleEval("Alkaline Phosphatase", "U/L", "40 - 129"),
                SampleEval("Total Protein", "g/dL", "6.0 - 8.3"),
                SampleEval("Albumin", "g/dL", "3.5 - 5.2")
            )
        ),
        SampleTest(
            "Blood Sugar - Fasting", 100.0, "Fluoride plasma",
            listOf(SampleEval("Glucose (Fasting)", "mg/dL", "70 - 100"))
        ),
        SampleTest(
            "Thyroid Profile (T3 T4 TSH)", 550.0, "Serum",
            listOf(
                SampleEval("T3 (Triiodothyronine)", "ng/dL", "80 - 200"),
                SampleEval("T4 (Thyroxine)", "µg/dL", "5.1 - 14.1"),
                SampleEval("TSH", "µIU/mL", "0.27 - 4.2")
            )
        ),
        SampleTest(
            "Renal Function Test (RFT)", 550.0, "Serum",
            listOf(
                SampleEval("Blood Urea", "mg/dL", "15 - 40"),
                SampleEval("Serum Creatinine", "mg/dL", "0.7 - 1.3"),
                SampleEval("Uric Acid", "mg/dL", "3.5 - 7.2"),
                SampleEval("Sodium", "mmol/L", "135 - 145", "Electrolytes"),
                SampleEval("Potassium", "mmol/L", "3.5 - 5.1", "Electrolytes")
            )
        )
    )
}
