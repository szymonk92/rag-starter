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
}
