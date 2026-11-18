package fun.luqing.dmws.web;

import fun.luqing.dmws.entity.dmw.economy.EconomyFishSubmission.Status;
import fun.luqing.dmws.repository.dmw.economy.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"dmws.fishing-web.enabled=true", "dmws.fishing-web.admin-password-hash=pbkdf2-sha256$310000$AAECAwQFBgcICQoLDA0ODw$f92pJW53GA3iaug9jH_lQ5bt_2TQOfl3FU9a37PGVYg"})
@ActiveProfiles("test")
@Transactional(transactionManager = "dmwTransactionManager")
class FishingWebTests {
    @Autowired WebApplicationContext context;
    @Autowired EconomyFishRepository fish;
    @Autowired EconomyFishSubmissionRepository submissions;
    MockMvc mvc; final ObjectMapper json = new ObjectMapper();
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    ObjectNode draft() { return json.createObjectNode().put("name", "网页测试鱼").put("description", "{player}收获{fish}").put("level", 3)
            .put("price", 120).put("difficulty", 45).put("dimensionsMin", 20).put("dimensionsMax", 45).put("special", false); }
    ObjectNode command() { var node = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("author", "网页投稿人"); node.set("fish", draft()); return node; }
    MockHttpSession login() throws Exception {
        var result = mvc.perform(post("/api/fishing/admin/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"fish-test-password\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    String csrf(MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get("/api/fishing/admin/session").session(session)).andReturn().getResponse().getContentAsString()).path("csrfToken").asText();
    }

    @Test void publicSubmissionIsPendingAndAdminRoutesRequireLoginAndCsrf() throws Exception {
        long before = fish.count(); var input = command();
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(input.toString())).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(before, fish.count());
        mvc.perform(get("/api/fishing/admin/submissions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/fishing/admin/submissions/1/approve").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var session = login(); var row = submissions.findByRequestId(input.path("requestId").asText()).orElseThrow();
        var decision = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("version", row.getVersion()).put("note", "审核通过"); decision.set("fish", draft());
        mvc.perform(post("/api/fishing/admin/submissions/" + row.getId() + "/approve").session(session).contentType("application/json").content(decision.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/fishing/admin/submissions/" + row.getId() + "/approve").session(session).header("X-CSRF-Token", csrf(session)).contentType("application/json").content(decision.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(before + 1, fish.count());
    }

    @Test void wrongPasswordsCrossOriginAndCoercedNumbersAreRejected() throws Exception {
        mvc.perform(post("/api/fishing/admin/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/fishing/submissions").header("Origin", "https://evil.invalid").header("Host", "localhost").contentType("application/json").content(command().toString()))
                .andExpect(status().isForbidden());
        var invalid = command(); ((ObjectNode) invalid.path("fish")).put("price", 1.9);
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
        invalid = command(); ((ObjectNode) invalid.path("fish")).put("special", "false");
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
    }

    @Test void publicProgressDoesNotDiscloseReviewerOrOtherSubmissions() throws Exception {
        var input = command();
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(input.toString())).andExpect(status().isCreated());
        String text = mvc.perform(get("/api/fishing/submissions/" + input.path("requestId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(text.contains("reviewer")); assertFalse(text.contains("网页投稿人"));
        mvc.perform(get("/api/fishing/submissions/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void loginRotatesSessionLogoutRevokesItAndResponsesAreNotCached() throws Exception {
        var previous = new MockHttpSession(); String oldId = previous.getId();
        var result = mvc.perform(post("/api/fishing/admin/login").session(previous).contentType("application/json")
                .content("{\"username\":\"admin\",\"password\":\"fish-test-password\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false); assertNotEquals(oldId, session.getId());
        mvc.perform(post("/api/fishing/admin/logout").session(session).header("X-CSRF-Token", csrf(session))).andExpect(status().isOk());
        mvc.perform(get("/api/fishing/admin/submissions")).andExpect(status().isUnauthorized());
    }

    @Test void xssStringsRemainJsonDataAndThePageHasCsp() throws Exception {
        var input = command(); ((ObjectNode) input.path("fish")).put("description", "<img src=x onerror=alert(1)> {player}");
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(input.toString())).andExpect(status().isCreated());
        assertEquals(Status.PENDING, submissions.findByRequestId(input.path("requestId").asText()).orElseThrow().getStatus());
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"));
        mvc.perform(post("/api/fishing/preview?rodLevel=45").contentType("application/json").content(draft().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reward").value("112.80"));
    }

    @Test void servesTheWebsiteAndAssetsAtRootWithoutLegacyRoutes() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"))
                .andExpect(header().exists("Content-Security-Policy"));
        String page = mvc.perform(get("/index.html")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("src=\"/app.js\""));
        assertFalse(page.contains("/fishing/"));
        for (String asset : new String[]{"/app.js", "/app.css", "/theme.js", "/theme.css", "/combat-tree.js", "/event-editor.js"}) {
            mvc.perform(get(asset)).andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"));
        }
        mvc.perform(get("/fishing")).andExpect(status().isNotFound());
        mvc.perform(get("/fishing/")).andExpect(status().isNotFound());
        mvc.perform(get("/fishing/index.html")).andExpect(status().isNotFound());
    }

    @Test void adminCanEditAnExistingFishWithARevisionAndRetainItsId() throws Exception {
        var session = login(); String token = csrf(session);
        var list = json.readTree(mvc.perform(get("/api/fishing/admin/fish").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var original = java.util.stream.StreamSupport.stream(list.spliterator(), false).filter(row -> row.path("fish").path("name").asText().equals("鲤鱼")).findFirst().orElseThrow();
        var body = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("revision", original.path("revision").asText()).put("enabled", false).put("note", "网页修改现有鱼");
        body.set("fish", draft().put("name", "鲤鱼网页版"));
        mvc.perform(post("/api/fishing/admin/fish/" + original.path("id").asInt()).session(session).header("X-CSRF-Token", token).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishedFishId").value(original.path("id").asInt()));
        assertEquals("鲤鱼网页版", fish.findById(original.path("id").asInt()).orElseThrow().getName());
        assertFalse(fish.findById(original.path("id").asInt()).orElseThrow().isEnabled());
    }

    @Test void eventSubmissionAndApprovalUseStringAmountsAndPublishOnlyAfterApproval() throws Exception {
        var event = json.createObjectNode().put("code", "api-story").put("name", "网页故事事件").put("kind", "MONEY").put("poolCode", "NEGATIVE").put("tickets", 1)
                .put("amountMin", "-20").put("amountMax", "80").put("amountStep", "1").put("enabled", true);
        event.putArray("texts").addObject().put("scene", "DESCRIPTION").put("template", "{player}的净变化是{net}").put("weight", 1).put("enabled", true);
        var body = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("author", "事件投稿人"); body.set("event", event);
        mvc.perform(post("/api/fishing/submissions").contentType("application/json").content(body.toString())).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("EVENT"));
        var row = submissions.findByRequestId(body.path("requestId").asText()).orElseThrow();
        var decision = json.createObjectNode().put("requestId", UUID.randomUUID().toString()).put("version", row.getVersion()).put("note", "事件审核通过"); decision.set("event", event);
        var session = login();
        mvc.perform(post("/api/fishing/admin/submissions/" + row.getId() + "/approve").session(session).header("X-CSRF-Token", csrf(session)).contentType("application/json").content(decision.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publishedEventCode").value("api-story"));
        event.put("amountMin", -20.1);
        mvc.perform(post("/api/fishing/event-preview").contentType("application/json").content(event.toString())).andExpect(status().isBadRequest());
    }
}
