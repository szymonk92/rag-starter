package dev.acme.rag.verify;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class AnswerCheckTest {
    final List<String> retrieved = List.of("deploys.md", "on-call.md");

    @Test void citedRetrievedSourceIsGrounded() {
        var r = AnswerCheck.check("No, deploys are blocked after 15:00 on Fridays [deploys.md].", retrieved);
        assertTrue(r.grounded());
        assertEquals(List.of("deploys.md"), r.cited());
        assertTrue(r.issues().isEmpty());
    }

    @Test void citationOutsideContextIsFlagged() { // model invents or misremembers a file
        var r = AnswerCheck.check("You get 26 days [leave.md] and 15 minutes [on-call.md].", retrieved);
        assertFalse(r.grounded());
        assertEquals(List.of("cited leave.md, which was not retrieved"), r.issues());
    }

    @Test void answerWithoutCitationIsFlagged() {
        var r = AnswerCheck.check("Deploys are blocked on Friday afternoons.", retrieved);
        assertFalse(r.grounded());
        assertEquals(List.of("no citation"), r.issues());
    }

    @Test void answerWithEmptyContextIsFlaggedEvenIfCited() {
        var r = AnswerCheck.check("Rotate every 90 days [security.md].", List.of());
        assertFalse(r.grounded());
        assertTrue(r.issues().contains("answered with no retrieved context"));
    }

    @Test void refusalIsGroundedAbstention() {
        for (String s : List.of("I don't know.", "I do not know based on the context.",
                "The context does not mention parental leave.", "I'm sorry, I can't answer that from the provided information.")) {
            var r = AnswerCheck.check(s, List.of());
            assertTrue(r.abstained(), s);
            assertTrue(r.grounded(), s);
        }
    }

    @Test void citationMatchIgnoresCaseAndPaths() {
        assertTrue(AnswerCheck.check("See [Deploys.md].", retrieved).grounded());
        assertTrue(AnswerCheck.check("See [docs/deploys.md].", retrieved).grounded());
    }

    // ---- literal checks: numbers, formulas, image paths must come from the context ----
    final String ctx = """
            The error budget is $$EB = (1 - SLO) \\times T$$ for a 30-day window, 43.2 minutes at 99.9%.
            ![Burn-down chart](img/error-budget-burn.png)
            Conference tickets up to 2,500 PLN are approved by your manager.""";
    final List<String> sla = List.of("sla.md");

    AnswerCheck.Result lit(String q, String a) { return AnswerCheck.check(q, a, sla, ctx); }

    @Test void verbatimLiteralsPass() {
        var r = lit("What is the error budget?", "It is $$ EB = (1 - SLO)\\times T $$, i.e. 43.2 minutes at 99.9% [sla.md]. See ![chart](img/error-budget-burn.png).");
        assertTrue(r.grounded(), r.issues().toString());
    }

    @Test void changedNumberIsFlagged() {
        assertEquals(List.of("number not in context: 43.8"), lit("q", "About 43.8 minutes [sla.md].").issues());
    }

    @Test void numbersFromQuestionAndThousandSeparatorsAreFine() {
        assertTrue(lit("Who approves a 3000 PLN ticket?", "Above 2500 PLN, so not the manager for 3,000 PLN [sla.md].").grounded());
    }

    @Test void alteredFormulaIsFlagged() {
        var r = lit("q", "Use $EB = (1 + SLO) \\times T$ [sla.md].");
        assertEquals(List.of("formula not in context: EB = (1 + SLO) \\times T"), r.issues());
    }

    @Test void inventedImagePathIsFlagged() {
        assertEquals(List.of("image not in context: img/budget.png"), lit("q", "See img/budget.png [sla.md].").issues());
    }
}
