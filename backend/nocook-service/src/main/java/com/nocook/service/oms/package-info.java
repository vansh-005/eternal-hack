/**
 * OMS access. {@link com.nocook.service.oms.OmsReadPort} is the single seam: {@code get(orderId)} returns
 * {@code OmsView.Available(snapshot)} or {@code OmsView.Unavailable(reason)}, never throws for transport errors,
 * never caches. {@link com.nocook.service.oms.OmsReadClient} is the RestClient implementation (nocook.oms.base-url,
 * 300 ms connect, 800 ms read, no retries). {@code getForEscalation} tags the metric path=escalation for B-04.
 * Ingestion throws {@link com.nocook.service.oms.OmsUnavailableException} so the container requeues.
 */
package com.nocook.service.oms;
