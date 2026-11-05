package fun.luqing.dmws.mcp;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;

/** MCP 工具的公开描述与参数 Schema，与 HTTP 协议处理分离。 */
@Component
public class McpToolCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public ArrayNode definitions() {
        ArrayNode tools = MAPPER.createArrayNode();

        tools.add(tool("dmw_search_books",
                "Search only books already present in DMW's local database (known/subscribed books) "
                        + "by book_id or book_name. This does NOT call Fanqie and does NOT search the "
                        + "global catalog. For global Fanqie search, use tnd_search_books on 8001.",
                objectSchema(List.of("q"),
                        property("q", "string", "Book title or local book ID"))));

        tools.add(tool("dmw_get_book",
                "Read one book already registered in DMW's local database.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"))));

        tools.add(tool("dmw_list_subscriptions",
                "List DMW subscriptions for one QQ group.",
                objectSchema(List.of("group_id"),
                        property("group_id", "integer", "QQ group ID"))));

        tools.add(tool("dmw_list_chapters",
                "List chapter metadata already stored in DMW. It does not return chapter text.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("offset", "integer", "Pagination offset"),
                        property("limit", "integer", "Page size, max 200"))));

        tools.add(tool("dmw_get_chapter_content",
                "Read one chapter via the unified local-first entry point. download_missing defaults to false. Missing content is partial, not success. Opt-in downloading returns a batch_id immediately; poll dmw_get_download_batch and then reread the original chapter. Never infer chapter order from item_id or downloader local index.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("chapter_id", "string", "Chapter item ID"),
                        property("order", "integer", "1-based chapter order"),
                        property("max_chars", "integer", "Maximum returned characters, 0..200000; 0 means no per-chapter cap"),
                        property("download_missing", "boolean", "Only true when the user explicitly permits downloading missing content"))));

        ObjectNode chapterRead = objectSchema(List.of("book_id", "item_ids"),
                property("book_id", "string", "Numeric string book ID"),
                property("item_ids", "array", "1-20 numeric string chapter IDs, in requested order"),
                property("download_missing", "boolean", "false by default; true only with explicit user download intent"),
                property("max_chars_per_chapter", "integer", "0..200000, 0 means no separate per-chapter cap"),
                property("max_total_chars", "integer", "1..1000000, default 100000; zero is invalid"),
                property("include_forward_nodes", "boolean", "Prepare literal text nodes for an explicitly requested merged forward; does not send"));
        ObjectNode readIds = (ObjectNode) chapterRead.path("properties").path("item_ids");
        readIds.put("minItems",1).put("maxItems",20).putObject("items").put("type","string").put("pattern","^[0-9]+$");
        tools.add(tool("dmw_get_chapters", "Read local DMW/downloader content without waiting for downloads. Inspect status, complete, missing_item_ids, download_error, refresh_required, and every truncation flag even if MCP isError=false. downloading is queued, not completed. After a batch ends, reread the ORIGINAL item_ids. Order comes only from DMW directory metadata; unknown order is omitted. forward_nodes contains available text only, at most 100 nodes/49000 characters; check forward_truncated and forward_complete. Send it only via an explicitly requested dmw_send_forward_message and explain missing/queued chapters separately.", chapterRead));
        ObjectNode checkSchema = objectSchema(List.of("book_id","item_ids"),
                property("book_id","string","Numeric string book ID"), property("item_ids","array","1-50 numeric string IDs; local availability only, no body or download"));
        ObjectNode checkIds = (ObjectNode) checkSchema.path("properties").path("item_ids");
        checkIds.put("minItems",1).put("maxItems",50).putObject("items").put("type","string").put("pattern","^[0-9]+$");
        tools.add(tool("dmw_check_chapters","Check downloader local availability only; EMPTY_BODY/NOT_DOWNLOADED are missing. No downloads.",checkSchema));
        tools.add(tool("dmw_get_download_batch","Query ONE explicit batch_id. done_with_errors is partial; canceling is not canceled. Inspect errors/cancel_error. Completion requires rereading original item_ids; never poll per chapter.",
                objectSchema(List.of("batch_id"),property("batch_id","string","Explicit batch ID returned by the downloader"))));
        tools.add(tool("dmw_cancel_download_batch","Request cancellation for an explicit batch only, with confirm=true and master operator. cancel_current=false waits for current work; true requests verified cooperative cancellation. canceling is a pending request; only canceled confirms the batch stopped. Never ignore cancel_error or automatically repeat an uncertain write.",
                objectSchema(List.of("batch_id","operator","confirm"),property("batch_id","string","Batch to cancel"),property("operator","integer","Requesting master"),property("confirm","boolean","Explicit user confirmation"),property("cancel_current","boolean","Default false; cooperative current-task cancellation requires explicit intent"))));
        tools.add(tool("dmw_retry_download_batch","Retry missing content of a terminal batch only, with confirm=true and master operator. Replace tracking ID with returned batch_id and retain retry_of. already_downloaded/nothing_to_retry creates no batch; reread original items. BATCH_ACTIVE/BATCH_TARGETS_UNKNOWN/BATCH_NOT_FOUND must be reported, not blindly retried.",
                objectSchema(List.of("batch_id","operator","confirm"),property("batch_id","string","Terminal batch ID"),property("operator","integer","Requesting master"),property("confirm","boolean","Explicit user confirmation"))));
        tools.add(tool("dmw_get_downloader_diagnostics","Read downloader connectivity, shared paths and worker heartbeat. diagnostics_ok=false is a diagnostic outcome, not HTTP failure. worker online means a heartbeat within 120 seconds, not a process-liveness guarantee.",objectSchema(List.of())));

        tools.add(tool("dmw_get_update_stats",
                "Read daily chapter and word-count statistics from DMW's local database. "
                        + "This is a read-only query and does not download chapters. Counts are body character counts including punctuation and whitespace, not the platform official word count. total_characters/character_count are explicit aliases for legacy total_words/word_count.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"),
                        property("start_date", "string", "Optional start date, yyyy-MM-dd"),
                        property("end_date", "string", "Optional end date, yyyy-MM-dd"))));

        tools.add(tool("dmw_get_update_heatmap",
                "Generate a GitHub-style update heatmap from DMW's database and return image/png "
                        + "base64. Counts are body character counts, not official platform word counts. Reads cached statistics only; never downloads chapters. Inspect data_complete and missing_word_count_chapters. If incomplete, report that clearly; use dmw_update_book to backfill in the background. On tool failure never claim the official chart succeeded. Label any separately plotted chart as an alternative.",
                objectSchema(List.of("book_id"),
                        property("book_id", "string", "DMW book ID"))));

        tools.add(tool("dmw_subscribe_book",
                "Subscribe a book to one QQ group. book_id should come from tnd_search_books on 8001 "
                        + "or an existing DMW book. Returns an immediate queued subscription job, not a success receipt. Poll dmw_get_update_job with job_id every 5 seconds until done=true; only report success after status=completed. Repeated pending requests reuse the same job. Existing subscriptions return their receipt without duplication. Chapter backfill runs separately (data_job_id). Requires confirm=true "
                        + "and a master operator.",
                objectSchema(List.of("group_id", "book_id", "operator", "confirm"),
                        property("group_id", "integer", "QQ group ID"),
                        property("book_id", "string", "Book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("at_all", "boolean", "Whether to mention all members"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_unsubscribe_book",
                "Remove a book subscription from one QQ group. Requires confirm=true and a master operator.",
                objectSchema(List.of("group_id", "book_id", "operator", "confirm"),
                        property("group_id", "integer", "QQ group ID"),
                        property("book_id", "string", "Book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_update_book",
                "Submit a background update of directory and chapter text for one DMW book. Returns job_id immediately. "
                        + "Poll dmw_get_update_job every 5 seconds until done=true. Active jobs for the same book are reused. Requires confirm=true and a master operator.",
                objectSchema(List.of("book_id", "operator", "confirm"),
                        property("book_id", "string", "DMW book ID"),
                        property("operator", "integer", "QQ user ID of the master"),
                        property("confirm", "boolean", "Must be true"))));

        tools.add(tool("dmw_economy_transfer",
                "Transfer wallet coins from the bot's own account by default, never the administrator. Pass user_id to act for the requesting user. If target_name uniquely and "
                        + "exactly matches one group card or nickname, the transfer executes directly. "
                        + "If the name is ambiguous or only fuzzy-matched, the tool returns candidates "
                        + "without executing. After the user chooses, retry with target_user_id and "
                        + "confirm=true. request_id is the idempotency key.",
                objectSchema(List.of("group_id", "amount", "request_id"),
                        property("group_id", "integer", "QQ group ID"),
                        property("user_id", "integer", "Current requester; omit to use bot account"),
                        property("target_user_id", "integer", "Resolved target QQ user ID"),
                        property("target_name", "string", "Target group card or nickname"),
                        property("amount", "string", "Amount, for example 100 or 1.5万"),
                        property("request_id", "string", "Idempotency key, max 50 safe characters"),
                        property("confirm", "boolean",
                                "Set true only after the user resolves an ambiguous target"))));

        tools.add(tool("dmw_economy_rob",
                "Attempt to rob a member of the QQ group as the bot's own account by default, never the administrator. Pass user_id only to act for the requesting user. Wallet-only loot is capped at 5000. "
                        + "The robber may be counterattacked into hospital or jailed; attacking an empty wallet may injure the victim. Inspect injury, injured_user_id, hospital_until, jail_until and crime. "
                        + "Hospitalized/jailed players cannot rob or be robbed; use dmw_economy_status for status and fee quotes. "
                        + "Use display_text for a humorous human reply. Never disclose target_balance_after or wallet_balance in chat; these are legacy machine fields. "
                        + "An owned electric baton with at least 20/100 energy automatically spends20, stays in inventory, and takes priority over queued boosts without stacking. Inspect electric_baton_used/electric_baton_energy. Charging batons with at least20 energy settle accrued charge, stop charging and spend20; below20 they keep charging. Empty-wallet attempts spend neither baton energy nor queued boosts and do not interrupt charging. "
                        + "Do not show raw injured QQ IDs or ISO timestamps/nanoseconds; hospital_until is the 24-hour forced-discharge deadline, not an early automatic recovery time. A unique exact target name or an explicit "
                        + "group member target_user_id executes directly. Ambiguous or fuzzy matches "
                        + "return candidates without executing; retry with target_user_id and "
                        + "confirm=true after the user chooses. request_id is the idempotency key.",
                objectSchema(List.of("group_id", "request_id"),
                        property("group_id", "integer", "QQ group ID"),
                        property("user_id", "integer", "Current requester; omit to use bot account"),
                        property("target_user_id", "integer", "Resolved target QQ user ID"),
                        property("target_name", "string", "Target group card or nickname"),
                        property("request_id", "string", "Idempotency key, max 50 safe characters"),
                        property("confirm", "boolean",
                                "Set true only after the user resolves an ambiguous target"))));

        tools.add(tool("dmw_economy_buy",
                "Buy an enabled economy-shop item for the QQ user who made the current request. "
                        + "Use this immediately when the user says they want to buy an item, for example "
                        + "'我要买红牛'; do not ask the user to send a chat command. The purchase is charged "
                        + "to user_id's wallet, automatically topping up any shortfall from the main bank. "
                        + "item accepts either an item code such as red-bull or a Chinese name such as 红牛. "
                        + "request_id is the idempotency key.",
                objectSchema(List.of("group_id", "user_id", "item", "request_id"),
                        property("group_id", "integer", "QQ group ID where the user sent the request"),
                        property("user_id", "integer", "QQ user ID of the requesting user"),
                        property("item", "string", "Item code or exact item name, such as red-bull or 红牛"),
                        property("quantity", "integer", "Quantity from 1 to 999; defaults to 1"),
                        property("request_id", "string",
                                "Idempotency key, max 50 safe characters; reuse it only for retries"))));

        ObjectNode useItemSchema = objectSchema(List.of("group_id", "item", "request_id"),
                property("group_id", "integer", "QQ group ID from the current conversation"),
                property("user_id", "integer", "Owner QQ user ID: use the requesting user's ID when acting for them; omit for the bot's own items"),
                property("item", "string", "Item code or exact Chinese name from shop/backpack"),
                property("quantity", "integer", "Quantity from 1 to 999; defaults to 1. Title and makeup cards require 1"),
                property("request_id", "string", "Stable idempotency key, max 50 safe characters; reuse for retries, use a new key only for a new requested use"));
        ObjectNode useProperties = (ObjectNode) useItemSchema.path("properties");
        ((ObjectNode) useProperties.path("group_id")).put("minimum", 1);
        ((ObjectNode) useProperties.path("user_id")).put("minimum", 1);
        ((ObjectNode) useProperties.path("quantity")).put("minimum", 1).put("maximum", 999).put("default", 1);
        ((ObjectNode) useProperties.path("request_id")).put("maxLength", 50).put("pattern", "^[A-Za-z0-9._:-]+$");
        tools.add(tool("dmw_economy_use_item",
                "Use an owned economy item immediately when the user requests it; do not ask them to send a chat command. "
                        + "Pass user_id from the current requesting user's context to use their items. Omit user_id only when the AI is using the bot's own items: "
                        + "this resolves dmws.bot-id (or the sole connected bot), never the configured local operator/master. "
                        + "The owner must be a member of group_id and OneBot must be connected. Names/codes are exact. "
                        + "Read backpack first when needed (as_bot=true for the bot). manual_usable=false items cannot be manually used; sign-bonus cards auto-consume during sign-in. "
                        + "Never use another member's inventory without their request or auto-buy an unavailable item. "
                        + "Effects apply to the item's owner; no separate target is supported. Check ok/code and report effect_message only after success. "
                        + "electric-baton is reusable and automatic during robbery; manual use only queries charge. electric-baton-charger is reusable, charges one lowest-energy baton at 1 point/minute, 100 minutes from empty. During robbery at least20 energy stops charging and spends20, below20 keeps charging. data.consumed_quantity is 0 for either reusable item. golden-basin clears ALL current crime, quantity must be 1 per use, at most two per local day, and does not shorten jail/hospital stays. "
                        + "Retry with the same request_id; DUPLICATE_REQUEST means no second consumption and must not trigger a fresh-key retry.",
                useItemSchema));

        tools.add(tool("dmw_economy_status",
                "Read player crime, injury, hospital/jail end times and current medical/bail fees. Settles overdue stays exactly once and may charge medical debt to main bank. "
                        + "No QQ connection is needed if the account ID is known. Pass user_id for a user; omit for the bot's own account (never the local operator/master). Times use the configured economy timezone.",
                objectSchema(List.of(), property("user_id", "integer", "Player QQ ID; defaults to bot's own account"),
                        property("group_id", "integer", "Optional current group for group-card name resolution"))));
        tools.add(tool("dmw_economy_get_location",
                "Query a player's current game location: HOSPITAL, JAIL or FREE (医院/监狱/自由活动). "
                        + "Pass user_id from the conversation for a player, or omit for the bot's own account. "
                        + "Use display_text for readable Chinese times. Queries settle overdue stays, so the result reflects releases immediately. "
                        + "All injuries permit a manual discharge before the uniform 24-hour forced-discharge deadline; do not claim a player leaves early automatically. "
                        + "This is a game-state location, not a physical address. No wallet balances are returned.",
                objectSchema(List.of(), property("user_id", "integer", "Player QQ user ID; omit for bot's own game location"),
                        property("group_id", "integer", "Optional current group for group-card name resolution"))));
        tools.add(tool("dmw_economy_discharge",
                "Discharge from hospital and pay diagnosis plus elapsed-minute fees. Wallet first, then main bank; medical shortfall becomes interest-free negative main-bank balance. "
                        + "Only act on the requesting user's explicit instruction: pass their user_id, or omit for the bot's own stay. The owner must be a current group member. "
                        + "Check last_hospital_fee and last_medical_loan. Reuse request_id on retries; a completed stay is never billed again.",
                objectSchema(List.of("group_id", "request_id"),
                        property("group_id", "integer", "Current QQ group ID"),
                        property("user_id", "integer", "Requesting user's QQ ID; omit for bot's own stay"),
                        property("request_id", "string", "Stable idempotency key, max 50 safe characters"))));
        tools.add(tool("dmw_economy_bail",
                "Pay coins to bail out another group member. The helper must be free, not hospitalized, and have sufficiently low crime (default <=10); cannot bail self. "
                        + "Pass helper's user_id from the requesting user's context, or omit for the bot's own coins. Both helper and target must be group members. "
                        + "First quote with confirm=false; this does not pay. Set confirm=true only when the user explicitly requests payment, never for a fee/status question. "
                        + "Charges helper's wallet then positive main-bank funds; no bail overdraft. Releasing does not clear the prisoner's crime. Reuse request_id on retries.",
                objectSchema(List.of("group_id", "target_user_id", "request_id"),
                        property("group_id", "integer", "Current QQ group ID"),
                        property("user_id", "integer", "Paying requester's QQ ID; omit for bot's own funds"),
                        property("target_user_id", "integer", "Prisoner QQ user ID"),
                        property("confirm", "boolean", "false/omitted quotes only; true confirms payment"),
                        property("request_id", "string", "Stable idempotency key, max 50 safe characters"))));

        ObjectNode forwardSchema = objectSchema(List.of("nodes"),
                property("group_id", "integer", "Target QQ group; provide exactly one of group_id or user_id"),
                property("user_id", "integer", "Target private QQ user"),
                property("nodes", "array", "1-100 nonempty plain text nodes, up to 50000 characters total"));
        ObjectNode nodes = (ObjectNode) forwardSchema.path("properties").path("nodes");
        nodes.putObject("items").put("type", "string").put("minLength", 1);
        nodes.put("minItems", 1).put("maxItems", 100);
        ArrayNode targets = forwardSchema.putArray("oneOf");
        targets.addObject().putArray("required").add("group_id");
        targets.addObject().putArray("required").add("user_id");
        tools.add(tool("dmw_send_forward_message",
                "Send a merged forward message to a QQ group or private user when requested. "
                        + "Each nodes entry becomes one message under the configured bot name. "
                        + "Text is literal, not CQ code. Returns a send receipt. "
                        + "Do not automatically retry on timeout or uncertain delivery, to avoid duplicates.",
                forwardSchema));
        tools.add(tool("dmw_get_update_job",
                "Read background update OR subscription job status. Pass the exact returned job_id, including sub- prefix. For a completed subscription, subscription_id is the receipt and data_job_id tracks chapter backfill. Queued/running is not subscription success. Status: queued, running, completed, partial, failed. "
                        + "done=true means finished; partial means some chapters failed. Records are in memory, last 100 jobs, cleared on restart.",
                objectSchema(List.of("job_id"), property("job_id", "string", "Job ID returned by dmw_update_book"))));
        tools.add(tool("dmw_economy_shop", "List enabled shop items with codes, names, descriptions, effect_type, manual_usable and exact decimal prices. Read-only, no QQ connection needed.",
                objectSchema(List.of())));
        tools.add(tool("dmw_economy_balance", "Read wallet and main-bank balances as exact decimal strings. No QQ connection needed. "
                + "Omit user_id for the bot's own account; pass the requesting user's ID to query that user. "
                + "Use as_bot=true without user_id to query the bot's own account instead. Bot identity needs dmws.bot-id or one connected bot.",
                objectSchema(List.of(), property("user_id", "integer", "QQ user ID; defaults to bot's own account"),
                        property("group_id", "integer", "Optional current group for group-card name resolution"),
                        property("as_bot", "boolean", "Query bot's own account; cannot combine true with user_id"))));
        tools.add(tool("dmw_economy_backpack", "Read owned items, quantities, effect_type and manual_usable, plus equipment.batons (energy, charging, minutes_to_full) and equipment.charger_owned. Query only; never changes charge or ownership. No QQ connection needed when the account ID is configured. "
                + "Omit both fields for the bot's own account; pass the requesting user's user_id to query that user. "
                + "Use as_bot=true without user_id for the bot's own backpack, matching dmw_economy_use_item's default owner. Bot identity needs dmws.bot-id or one connected bot.",
                objectSchema(List.of(), property("user_id", "integer", "QQ user ID; defaults to bot's own account"),
                        property("group_id", "integer", "Optional current group for group-card name resolution"),
                        property("as_bot", "boolean", "Query bot's own backpack; cannot combine true with user_id"))));
        for (var entry : tools) {
            ObjectNode definition = (ObjectNode) entry;
            if (EconomyMcpContext.WRITES.contains(definition.path("name").asText())) {
                definition.put("description", definition.path("description").asText()
                        + " Requires a trusted signed message context injected by the chat adapter, not generated by the model. "
                        + "Only the current requester or bot may be the actor. Use the returned display_text/message exactly; "
                        + "ok=false or isError=true means not completed. Never claim success after failure or timeout. "
                        + "The original QQ message, actor, action and item provide cross-transport deduplication.");
            }
        }
        return tools;
    }

    private ObjectNode tool(String name, String description, ObjectNode schema) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description + (name.startsWith("dmw_economy_")
                ? " In human replies, use player name fields or display_text, never raw QQ IDs. Action receipts should report the outcome and fee/effect only; mention medical debt only when positive, and reserve full status/balances for explicit queries."
                : ""));
        tool.set("inputSchema", schema);
        return tool;
    }

    private ObjectNode objectSchema(List<String> requiredFields, ObjectNode... properties) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (ObjectNode property : properties) {
            props.set(property.path("name").asText(), property.path("definition"));
        }
        ArrayNode required = schema.putArray("required");
        requiredFields.forEach(required::add);
        return schema;
    }

    private ObjectNode property(String name, String type, String description) {
        ObjectNode wrapper = MAPPER.createObjectNode();
        wrapper.put("name", name);
        ObjectNode definition = wrapper.putObject("definition");
        definition.put("type", type);
        definition.put("description", description);
        return wrapper;
    }
}
