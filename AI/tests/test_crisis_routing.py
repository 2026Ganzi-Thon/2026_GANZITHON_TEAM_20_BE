from types import SimpleNamespace
from unittest import IsolatedAsyncioTestCase, TestCase
from unittest.mock import AsyncMock, Mock, patch
from uuid import uuid4

import httpx

from crisis import CrisisDecision, assess_crisis
from mock_api import app, lifespan


class CrisisRoutingTest(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        settings = SimpleNamespace(
            mode="solar", service_token="", timeout=1, api_key="test-only", model="test-model"
        )
        self.enterContext(patch("mock_api.AnalysisSettings", return_value=settings))
        await self.enterAsyncContext(lifespan(app))
        self.client = await self.enterAsyncContext(
            httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")
        )
        content = {"summary": "합성 예시"}
        self.solar = AsyncMock(return_value=SimpleNamespace(model_dump=lambda: content))
        app.state.solar = SimpleNamespace(analyze=self.solar)
        self.ids = {"requestId": str(uuid4()), "attemptId": str(uuid4())}

    async def submit(self):
        response = await self.client.post(
            "/internal/v1/analyze", json={**self.ids, "text": "합성 테스트 입력"}
        )
        self.assertEqual(response.status_code, 200)
        return response.json()

    async def test_explicit_normal_decision_runs_generation(self):
        assessor = Mock(return_value=CrisisDecision.GENERATE)
        app.state.crisis_assessor = assessor

        self.assertEqual(
            await self.submit(), {**self.ids, "outcome": "SUCCESS", "content": {"summary": "합성 예시"}}
        )
        assessor.assert_called_once_with("합성 테스트 입력")
        self.solar.assert_awaited_once_with("합성 테스트 입력")

    async def test_guidance_does_not_call_solar(self):
        app.state.crisis_assessor = Mock(return_value=CrisisDecision.GUIDANCE)

        self.assertEqual(
            await self.submit(), {**self.ids, "outcome": "SUPPORT_GUIDANCE", "guidanceCode": "S16"}
        )
        self.solar.assert_not_awaited()

    async def test_missing_assessor_or_invalid_decision_cannot_fall_through(self):
        for assessor in (None, Mock(return_value=CrisisDecision.FAILED), Mock(return_value="GENERATE")):
            with self.subTest(assessor=assessor):
                app.state.crisis_assessor = assessor
                self.assertEqual(
                    await self.submit(), {**self.ids, "outcome": "FAILURE", "failureCode": "ASSESSMENT_FAILED"}
                )
        self.solar.assert_not_awaited()

    async def test_timeout_does_not_log_assessor_exception_text_or_call_solar(self):
        app.state.crisis_assessor = Mock(side_effect=TimeoutError("synthetic exception content"))

        with self.assertLogs("mock_api", level="WARNING") as logs:
            self.assertEqual(
                await self.submit(), {**self.ids, "outcome": "FAILURE", "failureCode": "ASSESSMENT_FAILED"}
            )
        self.assertNotIn("synthetic exception content", "\n".join(logs.output))
        self.solar.assert_not_awaited()

    async def test_default_assessor_routes_before_solar(self):
        response = await self.client.post(
            "/internal/v1/analyze",
            json={**self.ids, "text": "지금 너무 힘들고 죽고 싶어요.", "anxiety": 1},
        )
        self.assertEqual(response.json(), {**self.ids, "outcome": "SUPPORT_GUIDANCE", "guidanceCode": "S16"})
        self.solar.assert_not_awaited()


class CrisisAssessmentTest(TestCase):
    def test_representative_signals_and_contexts(self):
        cases = [
            ("지금 죽고 싶어요.", CrisisDecision.GUIDANCE),
            ("자해를 했어요.", CrisisDecision.GUIDANCE),
            ("오늘 제 삶을 끝내려고 해요.", CrisisDecision.GUIDANCE),
            ("내 삶을 끝낼 계획을 세웠어요.", CrisisDecision.GUIDANCE),
            ("자살 준비 중이에요.", CrisisDecision.GUIDANCE),
            ("예전에는 힘들었지만 지금은 죽고 싶어요.", CrisisDecision.GUIDANCE),
            ("지금 죽\u200b고싶어요.", CrisisDecision.GUIDANCE),
            ("I want to die.", CrisisDecision.GUIDANCE),
            ("죽고 싶지는 않아요.", CrisisDecision.FAILED),
            ("친구가 죽고 싶다고 했어요.", CrisisDecision.FAILED),
            ("예전에 죽고 싶었어요.", CrisisDecision.FAILED),
            ("영화에서 “죽고 싶다”는 대사를 들었어요.", CrisisDecision.FAILED),
            ("그냥 사라지고 싶은 기분이에요.", CrisisDecision.FAILED),
            ("살 이유가 없는 것 같아요.", CrisisDecision.FAILED),
            ("자살 관련 기사를 봤어요.", CrisisDecision.FAILED),
            ("내일 발표가 너무 걱정돼요.", CrisisDecision.GENERATE),
            ("창피해서 죽을 만큼 힘들었어요.", CrisisDecision.GENERATE),
        ]
        for text, expected in cases:
            with self.subTest(text=text):
                self.assertIs(assess_crisis(text), expected)
