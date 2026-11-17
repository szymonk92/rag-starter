"""Is the formula in a RAG answer the same maths as the formula in the source?

AnswerCheck (Java) only accepts formulas copied verbatim. Models often rewrite them: T(1-SLO) for (1 - SLO) \\times T.
This decides equivalence symbolically, with a numeric fallback for what sympy cannot simplify.

    uv run --with sympy==1.14.0 --with antlr4-python3-runtime==4.11 python eval/latex_equiv.py
"""
import random
import re

from sympy import simplify
from sympy.parsing.latex import parse_latex


def normalize(tex: str) -> str:
    # multi-letter names are one variable (sympy would read SLO as S*L*O), and T(1-x) is a product, not a call
    tex = re.sub(r"(?<![\\A-Za-z])([A-Za-z]{2,})", r"\\mathit{\1}", tex)
    return re.sub(r"([A-Za-z}])\s*\(", r"\1 \\cdot (", tex)


def equivalent(a: str, b: str, trials: int = 20) -> bool:
    ea, eb = parse_latex(normalize(a)), parse_latex(normalize(b))
    if getattr(ea, "is_Relational", False):  # "EB = ..." -> compare right-hand sides
        ea, eb = ea.rhs, eb.rhs
    if simplify(ea - eb) == 0:
        return True
    # ponytail: random-point fallback; can be fooled by expressions equal only on a measure-zero set
    syms = sorted(ea.free_symbols | eb.free_symbols, key=str)
    for _ in range(trials):
        point = {s: random.uniform(0.1, 10) for s in syms}
        if abs(complex(ea.evalf(subs=point)) - complex(eb.evalf(subs=point))) > 1e-9:
            return False
    return True


if __name__ == "__main__":
    src = r"EB = (1 - SLO) \times T"
    assert equivalent(src, r"EB = T(1-SLO)")
    assert equivalent(src, r"EB = T - SLO \cdot T")
    assert not equivalent(src, r"EB = (1 + SLO) \times T")
    assert not equivalent(src, r"EB = (1 - SLO) / T")
    avail = r"A = \frac{good}{good + bad}"
    assert equivalent(avail, r"A = \frac{1}{1 + \frac{bad}{good}}")
    assert not equivalent(avail, r"A = \frac{good}{bad}")
    print("latex_equiv: 6 checks passed")
