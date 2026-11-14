import asyncio
import re
from contextlib import asynccontextmanager
from hmac import compare_digest
from typing import Annotated
from uuid import UUID

import httpx
from fastapi import FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator

from solar import AnalysisSettings, SolarAnalyzer, SolarFailure


NON_WHITESPACE = re.compile(
    r"[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]"
)
SURROGATE = re.compile(r"[\uD800-\uDFFF]")

@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = AnalysisSettings()
    app.state.service_token = settings.service_token
    app.state.solar = None
    if settings.mode == "mock":
        yield
        return
    async with httpx.AsyncClient(timeout=settings.timeout) as http:
        app.state.solar = SolarAnalyzer(http, settings.api_key, settings.model, settings.timeout)
        yield


app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)


class AnalysisRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", hide_input_in_errors=True)

    requestId: UUID
    attemptId: UUID
    text: Annotated[str, Field(strict=True, min_length=1, max_length=500)]
    anxiety: Annotated[int, Field(strict=True, ge=1, le=5)] | None = None
    memo: Annotated[str, Field(strict=True, max_length=100)] | None = None

    @field_validator("text")
    @classmethod
    def validate_text(cls, value: str) -> str:
        if SURROGATE.search(value) or not NON_WHITESPACE.search(value):
            raise ValueError("Invalid worry text")
        return value

    @field_validator("anxiety", mode="before")
    @classmethod
    def validate_anxiety(cls, value: object) -> object:
        if value is None:
            raise ValueError("Anxiety must be an integer when provided")
        if isinstance(value, float) and value.is_integer():
            return int(value)
        return value

    @field_validator("memo")
    @classmethod
    def validate_memo(cls, value: str | None) -> str:
        if value is None or SURROGATE.search(value):
            raise ValueError("Memo must be valid text when provided")
        return value


@app.exception_handler(RequestValidationError)
@app.exception_handler(400)
async def invalid_request(_request: Request, _error: Exception) -> JSONResponse:
    return JSONResponse({"code": "INVALID_REQUEST"}, status_code=400)


@app.post("/internal/v1/analyze")
async def analyze(
    request: Request, body: AnalysisRequest, authorization: Annotated[str | None, Header()] = None
):
    service_token = request.app.state.service_token
    if service_token and not compare_digest(
        (authorization or "").encode(), f"Bearer {service_token}".encode()
    ):
        return JSONResponse({"code": "UNAUTHORIZED"}, status_code=401)

    ids = {"requestId": body.requestId, "attemptId": body.attemptId}
    solar = request.app.state.solar
    if solar is not None:
        try:
            content = await solar.analyze(body.text)
        except SolarFailure as error:
            return {**ids, "outcome": "FAILURE", "failureCode": str(error)}
        return {**ids, "outcome": "SUCCESS", "content": content.model_dump()}

    match body.text:
        case "[mock:guidance]":
            return {**ids, "outcome": "SUPPORT_GUIDANCE", "guidanceCode": "S16"}
        case "[mock:failure]":
            return {**ids, "outcome": "FAILURE", "failureCode": "INTERNAL_ERROR"}
        case "[mock:timeout]":
            await asyncio.sleep(4)
            return {**ids, "outcome": "FAILURE", "failureCode": "PROVIDER_TIMEOUT"}

    return {
        **ids,
        "outcome": "SUCCESS",
        "content": {
            "summary": "Python 테스트 응답으로 분석 연동을 확인했어요.",
            "recommendation": {
                "type": "ACTION",
                "reason": "지금 할 수 있는 작은 행동부터 시작해 볼 수 있어요.",
            },
            "actionSteps": [
                "지금 할 수 있는 한 가지를 골라요.",
                "할 수 있는 만큼 시작해요.",
                "해 본 일을 짧게 적어요.",
            ],
            "restGuide": "잠시 어깨에 힘을 빼고 천천히 숨을 쉬어요.",
            "empathyMessage": "마음에 걸리는 일이 있다는 것만으로도 지칠 수 있어요.",
        },
    }
