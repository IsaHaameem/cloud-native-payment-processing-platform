package dev.paymentflow;

import dev.paymentflow.model.PaymentResponse;
import dev.paymentflow.model.WebhookDeliveryResponse;
import dev.paymentflow.resources.Payments;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaginationTest {

    @Test
    void aForLoopOverACursorListCrossesPageBoundariesAndReusesTheFilters() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ok(200, "{\"data\":[{\"id\":\"pay_1\"}],\"hasMore\":true,\"nextCursor\":\"c1\"}"),
                FakeHttp.Turn.ok(200, "{\"data\":[{\"id\":\"pay_2\"}],\"hasMore\":false}"));
        PaymentFlow client = FakeHttp.client(http);

        List<String> ids = new ArrayList<>();
        for (PaymentResponse payment : client.payments().list(Payments.listParams().status("captured"), null)) {
            ids.add(payment.id());
        }

        assertEquals(List.of("pay_1", "pay_2"), ids);
        assertEquals(2, http.calls());
        assertTrue(http.requests.get(0).uri().getQuery().contains("status=captured"));
        String secondQuery = http.requests.get(1).uri().getQuery();
        assertTrue(secondQuery.contains("starting_after=c1"), secondQuery);
        assertTrue(secondQuery.contains("status=captured"), "the cursor page keeps the original filter");
    }

    @Test
    void aCursorPageWithNoHasMoreFlagButACursorIsNotTreatedAsTheLast() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ok(200, "{\"data\":[{\"id\":\"pay_1\"}],\"nextCursor\":\"c1\"}"),
                FakeHttp.Turn.ok(200, "{\"data\":[{\"id\":\"pay_2\"}]}"));
        PaymentFlow client = FakeHttp.client(http);

        CursorPage<PaymentResponse> first = client.payments().list();
        assertTrue(first.hasMore());
        assertEquals(2, first.toList(100).size());
    }

    @Test
    void anOffsetListPaginatesByIndex() {
        FakeHttp http = new FakeHttp(
                FakeHttp.Turn.ok(200, "{\"content\":[{\"id\":\"wd_1\"}],\"page\":0,\"size\":1,"
                        + "\"totalElements\":2,\"totalPages\":2,\"last\":false}"),
                FakeHttp.Turn.ok(200, "{\"content\":[{\"id\":\"wd_2\"}],\"page\":1,\"size\":1,"
                        + "\"totalElements\":2,\"totalPages\":2,\"last\":true}"));
        PaymentFlow client = FakeHttp.client(http);

        OffsetPage<WebhookDeliveryResponse> page = client.webhookDeliveries().list();
        assertEquals(2, page.totalElements());
        assertTrue(page.hasMore());

        List<String> ids = new ArrayList<>();
        for (WebhookDeliveryResponse delivery : page) {
            ids.add(delivery.id());
        }
        assertEquals(List.of("wd_1", "wd_2"), ids);
        assertTrue(http.requests.get(1).uri().getQuery().contains("page=1"));

        OffsetPage<WebhookDeliveryResponse> last = page.nextPage();
        assertFalse(last.hasMore());
    }
}
