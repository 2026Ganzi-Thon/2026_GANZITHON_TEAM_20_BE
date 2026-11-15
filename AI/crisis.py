import re
import unicodedata
from enum import Enum, auto


class CrisisDecision(Enum):
    GUIDANCE = auto()
    GENERATE = auto()
    FAILED = auto()


# These signals select an app response. They do not estimate clinical risk.
_CLAUSE = re.compile(r"[.!?。！？;\n]+|(?:하지만|그런데|그래도|다만|지만)\s*")
_DIRECT = re.compile(
    r"죽\s*고\s*싶|죽\s*고자\s*하|죽\s*어\s*야겠|살\s*고\s*싶\s*지"
    r"|(?:(?:내|제)\s*)?(?:목숨|삶|생명)\s*(?:을)?\s*"
    r"(?:(?:끊|끝\s*내)(?:겠|기로|으려|려|고\s*싶|을\s*(?:거|계획|생각))|끝\s*낼\s*(?:거|계획|생각))"
    r"|(?:자\s*살|자\s*해|극단적\s*선택)\s*(?:을|를)?\s*"
    r"(?:하고\s*싶|하겠|하려|할\s*(?:거|생각|계획)|시도\s*(?:했|하)|했"
    r"|계획\s*(?:이|을|했|중)|준비\s*(?:중|했|하고))"
    r"|\bi\s+(?:want\s+to\s+die|(?:plan|want|intend)\s+to\s+(?:kill|hurt)\s+myself"
    r"|(?:am\s+going|have\s+a\s+plan)\s+to\s+(?:kill|hurt)\s+myself)\b"
)
_CANDIDATE = re.compile(
    r"자\s*살|자\s*해|극단적\s*선택|죽\s*고\s*싶|살\s*고\s*싶\s*지"
    r"|사라지고\s*싶|살\s*이유(?:가|는)?\s*없|(?:목숨|삶|생명)을?\s*(?:끊|끝\s*내|끝\s*낼)"
    r"|죽을\s*(?:생각|계획|거(?:야|예요))"
    r"|\b(?:suicid(?:e|al)|self[- ]harm|want\s+to\s+die|kill\s+myself|hurt\s+myself)\b"
)
_CONTEXT = re.compile(
    r"과거|예전|작년|어렸을\s*때|그때는?|한\s*적|했었|싶었"
    r"|(?:친구|동료|가족|동생|언니|오빠|형|누나|지인|주인공|다른\s*사람)\s*(?:이|가|은|는)"
    r"|(?:뉴스|기사|영화|드라마|소설|대사|인용|번역|예문|가사)"
    r"|싶(?:지(?:는|도)?\s*않|은\s*건\s*아니|다는\s*뜻(?:은)?\s*아니)"
    r"|(?:할|하겠다는|하고\s*싶다는)\s*(?:생각|뜻|마음|계획)(?:은|이|도)?\s*(?:없|아니)"
    r"|(?:자\s*살|자\s*해)\s*(?:은|는|을)?\s*(?:하지\s*않|안\s*해)"
    r"|\b(?:used\s+to|last\s+year|my\s+friend|quote|movie|song|do\s+not|don't)\b"
    r"|[\"“”‘’]"
)


def assess_crisis(text: str) -> CrisisDecision:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    normalized = "".join(char for char in normalized if unicodedata.category(char) != "Cf")
    possible_signal = False
    for clause in _CLAUSE.split(normalized):
        direct = _DIRECT.search(clause)
        if direct and not _CONTEXT.search(clause):
            return CrisisDecision.GUIDANCE
        if direct or _CANDIDATE.search(clause):
            possible_signal = True
    return CrisisDecision.FAILED if possible_signal else CrisisDecision.GENERATE
