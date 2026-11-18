import os
from unittest import IsolatedAsyncioTestCase, TestCase
from unittest.mock import patch
from uuid import uuid4

import httpx

from mock_api import app, lifespan
from solar import AnalysisSettings


TOKEN = "synthetic-internal-token"


class RuntimeSettingsTest(TestCase):
    def test_default_and_explicit_prod_never_load_dotenv(self):
        for configured in ({}, {"NIGHTVAULT_ENV": "prod"}):
            with self.subTest(configured=configured):
                with patch.dict(os.environ, {**configured, "NIGHTVAULT_AI_TOKEN": TOKEN}, clear=True):
                    with patch("solar.dotenv_values") as dotenv:
                        settings = AnalysisSettings()
                    dotenv.assert_not_called()
                    self.assertEqual(settings.service_token, TOKEN)
                    self.assertEqual(settings.timeout, 24)

    def test_explicit_local_reads_dotenv_and_environment_takes_precedence(self):
        with patch.dict(os.environ, {"NIGHTVAULT_ENV": "local", "NIGHTVAULT_AI_TOKEN": TOKEN}, clear=True):
            with patch("solar.dotenv_values", return_value={
                "NIGHTVAULT_AI_TOKEN": "synthetic-file-token", "UPSTAGE_TIMEOUT_SECONDS": "10",
            }) as dotenv:
                settings = AnalysisSettings()
        dotenv.assert_called_once()
        self.assertEqual(settings.service_token, TOKEN)
        self.assertEqual(settings.timeout, 10)

    def test_token_is_required_in_both_modes_and_runtimes(self):
        for runtime in ("local", "prod"):
            for mode in ("mock", "solar"):
                with self.subTest(runtime=runtime, mode=mode):
                    env = {"NIGHTVAULT_ENV": runtime, "NIGHTVAULT_AI_MODE": mode}
                    with patch.dict(os.environ, env, clear=True), patch("solar.dotenv_values", return_value={}):
                        with self.assertRaisesRegex(ValueError, "NIGHTVAULT_AI_TOKEN must be"):
                            AnalysisSettings()

    def test_invalid_token_is_rejected_without_echoing_its_value(self):
        for token in ("", "  ", "token with spaces", "token\nvalue", "비밀토큰"):
            with self.subTest(token=token):
                with patch.dict(os.environ, {"NIGHTVAULT_AI_TOKEN": token}, clear=True):
                    with self.assertRaisesRegex(ValueError, "^NIGHTVAULT_AI_TOKEN must be") as failure:
                        AnalysisSettings()
                if token.strip():
                    self.assertNotIn(token, str(failure.exception))

    def test_prod_cannot_take_credentials_from_dotenv(self):
        with patch.dict(os.environ, {"NIGHTVAULT_ENV": "prod"}, clear=True):
            with patch("solar.dotenv_values", return_value={"NIGHTVAULT_AI_TOKEN": TOKEN}) as dotenv:
                with self.assertRaisesRegex(ValueError, "NIGHTVAULT_AI_TOKEN must be"):
                    AnalysisSettings()
            dotenv.assert_not_called()

    def test_file_cannot_select_the_local_runtime(self):
        with patch.dict(os.environ, {}, clear=True):
            with patch("solar.dotenv_values", return_value={
                "NIGHTVAULT_ENV": "local", "NIGHTVAULT_AI_TOKEN": TOKEN,
            }) as dotenv:
                with self.assertRaises(ValueError):
                    AnalysisSettings()
            dotenv.assert_not_called()

    def test_mixed_profiles_fail_before_dotenv_is_read(self):
        for profiles in ("prod", "prod,local", "local, prod"):
            with self.subTest(profiles=profiles):
                with patch.dict(os.environ, {"NIGHTVAULT_ENV": "local", "SPRING_PROFILES_ACTIVE": profiles}, clear=True):
                    with patch("solar.dotenv_values") as dotenv:
                        with self.assertRaisesRegex(ValueError, "cannot be combined"):
                            AnalysisSettings()
                        dotenv.assert_not_called()

    def test_invalid_runtime_and_timeouts_are_rejected(self):
        for overrides in ({"NIGHTVAULT_ENV": "production"}, {"UPSTAGE_TIMEOUT_SECONDS": "0"},
                          {"UPSTAGE_TIMEOUT_SECONDS": "nan"}, {"UPSTAGE_TIMEOUT_SECONDS": "inf"}):
            with self.subTest(overrides=overrides):
                with patch.dict(os.environ, {"NIGHTVAULT_AI_TOKEN": TOKEN, **overrides}, clear=True):
                    with self.assertRaises(ValueError):
                        AnalysisSettings()


class InternalAuthenticationTest(IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.enterContext(patch.dict(os.environ, {
            "NIGHTVAULT_ENV": "prod", "NIGHTVAULT_AI_MODE": "mock", "NIGHTVAULT_AI_TOKEN": TOKEN,
        }, clear=True))
        await self.enterAsyncContext(lifespan(app))
        self.client = await self.enterAsyncContext(httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test", trust_env=False,
        ))
        self.body = {"requestId": str(uuid4()), "attemptId": str(uuid4()), "text": "합성 입력"}

    async def test_missing_and_wrong_tokens_are_rejected(self):
        for headers in ({}, {"Authorization": "Bearer wrong-token"}, {"Authorization": TOKEN}):
            with self.subTest(headers=headers):
                response = await self.client.post("/internal/v1/analyze", json=self.body, headers=headers)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.json(), {"code": "UNAUTHORIZED"})

    async def test_correct_token_can_call_the_mock_provider(self):
        response = await self.client.post("/internal/v1/analyze", json=self.body,
                                          headers={"Authorization": f"Bearer {TOKEN}"})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["outcome"], "SUCCESS")

    async def test_empty_runtime_token_never_bypasses_authentication(self):
        app.state.service_token = ""
        response = await self.client.post("/internal/v1/analyze", json=self.body)
        self.assertEqual(response.status_code, 401)


class StartupTest(IsolatedAsyncioTestCase):
    async def test_missing_token_prevents_application_startup(self):
        with patch.dict(os.environ, {"NIGHTVAULT_ENV": "prod"}, clear=True):
            with self.assertRaisesRegex(ValueError, "NIGHTVAULT_AI_TOKEN must be"):
                async with lifespan(app):
                    self.fail("Application startup should have failed")
