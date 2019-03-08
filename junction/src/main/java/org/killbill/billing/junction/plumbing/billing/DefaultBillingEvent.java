/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2018 Groupon, Inc
 * Copyright 2014-2018 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.junction.plumbing.billing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.joda.time.DateTime;
import org.killbill.billing.catalog.api.BillingAlignment;
import org.killbill.billing.catalog.api.BillingPeriod;
import org.killbill.billing.catalog.api.Catalog;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.Currency;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.catalog.api.Usage;
import org.killbill.billing.junction.BillingEvent;
import org.killbill.billing.subscription.api.SubscriptionBase;
import org.killbill.billing.subscription.api.SubscriptionBaseTransitionType;
import org.killbill.billing.subscription.api.user.SubscriptionBillingEvent;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class DefaultBillingEvent implements BillingEvent {

    private final UUID subscriptionId;
    private final UUID bundleId;

    private final int billCycleDayLocal;
    private final BillingAlignment billingAlignment;

    private final DateTime effectiveDate;

    private final PlanPhase planPhase;
    private final Plan plan;
    private final BillingPeriod billingPeriod;

    private final BigDecimal fixedPrice;
    private final BigDecimal recurringPrice;
    private final Currency currency;
    private final String description;
    private final SubscriptionBaseTransitionType type;
    private final Long totalOrdering;

    private final List<Usage> usages;

    private final boolean isCancelledOrBlocked;

    private final DateTime catalogEffectiveDate;


    // TODO Ugly, can that go completely ?
    private final SubscriptionBase subscription;

    public DefaultBillingEvent(final SubscriptionBillingEvent inputEvent,
                               final SubscriptionBase subscription,
                               final int billCycleDayLocal,
                               final BillingAlignment billingAlignment,
                               final Currency currency,
                               final Catalog catalog) throws CatalogApiException {

        this.subscription = subscription;

        this.subscriptionId = subscription.getId();
        this.bundleId = subscription.getBundleId();

        this.isCancelledOrBlocked = inputEvent.getType() == SubscriptionBaseTransitionType.CANCEL;

        this.type = inputEvent.getType();
        this.plan = catalog.findPlan(inputEvent.getPlanName(), inputEvent.getEffectiveDate(), subscription.getStartDate());
        this.planPhase = this.plan.findPhase(inputEvent.getPlanPhaseName());

        this.catalogEffectiveDate = new DateTime(plan.getCatalog().getEffectiveDate());

        this.currency = currency;
        this.billCycleDayLocal = billCycleDayLocal;
        this.billingAlignment = billingAlignment;
        this.description = inputEvent.getType().toString();
        this.effectiveDate = inputEvent.getEffectiveDate();
        this.totalOrdering = inputEvent.getTotalOrdering();

        this.billingPeriod = computeRecurringBillingPeriod();
        this.fixedPrice =  computeFixedPrice();
        this.recurringPrice = computeRecurringPrice();
        this.usages = computeUsages();
    }

    public DefaultBillingEvent(final SubscriptionBase subscription,
                               final DateTime effectiveDate,
                               final Plan plan,
                               final PlanPhase planPhase,
                               final BigDecimal fixedPrice,
                               final BigDecimal recurringPrice,
                               final Currency currency,
                               final BillingPeriod billingPeriod,
                               final int billCycleDayLocal,
                               final String description,
                               final long totalOrdering,
                               final SubscriptionBaseTransitionType type,
                               final boolean isDisableEvent) throws CatalogApiException {

        this.subscription = subscription;


        this.subscriptionId = subscription.getId();
        this.bundleId = subscription.getBundleId();
        this.effectiveDate = effectiveDate;

        this.isCancelledOrBlocked = isDisableEvent;

        this.plan = plan;
        this.planPhase = planPhase;
        this.billingPeriod = billingPeriod;
        this.fixedPrice = fixedPrice;
        this.recurringPrice = recurringPrice;
        this.currency = currency;
        this.billCycleDayLocal = billCycleDayLocal;
        this.description = description;
        this.type = type;
        this.totalOrdering = totalOrdering;
        this.usages = computeUsages();
        this.catalogEffectiveDate = new DateTime(plan.getCatalog().getEffectiveDate());
        this.billingAlignment = null;
    }

    SubscriptionBase getSubscription() {
        return subscription;
    }

    @Override
    public int compareTo(final BillingEvent e1) {
        if (!subscriptionId.equals(e1.getSubscriptionId())) { // First order by subscription
            return subscriptionId.compareTo(e1.getSubscriptionId());
        } else { // subscriptions are the same
            if (!getEffectiveDate().equals(e1.getEffectiveDate())) { // Secondly order by date
                return getEffectiveDate().compareTo(e1.getEffectiveDate());
            } else { // dates and subscriptions are the same
                // If an subscription event and an overdue event happen at the exact same time,
                // we assume we want the subscription event before the overdue event when entering
                // the overdue period, and vice-versa when exiting the overdue period
                if (SubscriptionBaseTransitionType.START_BILLING_DISABLED.equals(getTransitionType())) {
                    if (SubscriptionBaseTransitionType.END_BILLING_DISABLED.equals(e1.getTransitionType())) {
                        // Make sure to always have START before END
                        return -1;
                    } else {
                        return 1;
                    }
                } else if (SubscriptionBaseTransitionType.START_BILLING_DISABLED.equals(e1.getTransitionType())) {
                    if (SubscriptionBaseTransitionType.END_BILLING_DISABLED.equals(getTransitionType())) {
                        // Make sure to always have START before END
                        return 1;
                    } else {
                        return -1;
                    }
                } else if (SubscriptionBaseTransitionType.END_BILLING_DISABLED.equals(getTransitionType())) {
                    if (SubscriptionBaseTransitionType.START_BILLING_DISABLED.equals(e1.getTransitionType())) {
                        // Make sure to always have START before END
                        return 1;
                    } else {
                        return -1;
                    }
                } else if (SubscriptionBaseTransitionType.END_BILLING_DISABLED.equals(e1.getTransitionType())) {
                    if (SubscriptionBaseTransitionType.START_BILLING_DISABLED.equals(getTransitionType())) {
                        // Make sure to always have START before END
                        return -1;
                    } else {
                        return 1;
                    }
                } else {
                    return getTotalOrdering().compareTo(e1.getTotalOrdering());
                }
            }
        }
    }

    @Override
    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    @Override
    public UUID getBundleId() {
        return bundleId;
    }

    @Override
    public int getBillCycleDayLocal() {
        return billCycleDayLocal;
    }

    @Override
    public BillingAlignment getBillingAlignment() {
        return billingAlignment;
    }


    @Override
    public DateTime getEffectiveDate() {
        return effectiveDate;
    }

    @Override
    public PlanPhase getPlanPhase() {
        return planPhase;
    }

    @Override
    public Plan getPlan() {
        return plan;
    }

    @Override
    public BillingPeriod getBillingPeriod() {
        return billingPeriod;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public BigDecimal getFixedPrice() {
        return fixedPrice;
    }

    @Override
    public BigDecimal getRecurringPrice() {
        return recurringPrice;
    }

    @Override
    public Currency getCurrency() {
        return currency;
    }

    @Override
    public SubscriptionBaseTransitionType getTransitionType() {
        return type;
    }

    @Override
    public Long getTotalOrdering() {
        return totalOrdering;
    }

    @Override
    public List<Usage> getUsages() {
        return usages;
    }

    @Override
    public String toString() {
        // Note: we don't use all fields here, as the output would be overwhelming
        // (these events are printed in the logs in junction and invoice).
        final StringBuilder sb = new StringBuilder();
        sb.append("DefaultBillingEvent");
        sb.append("{type=").append(type);
        sb.append(", effectiveDate=").append(effectiveDate);
        sb.append(", planPhaseName=").append(planPhase.getName());
        sb.append(", subscriptionId=").append(subscriptionId);
        sb.append(", totalOrdering=").append(totalOrdering);
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final DefaultBillingEvent that = (DefaultBillingEvent) o;

        if (subscriptionId != null ? !subscriptionId.equals(that.subscriptionId) : that.subscriptionId != null) {
            return false;
        }
        if (bundleId != null ? !bundleId.equals(that.bundleId) : that.bundleId != null) {
            return false;
        }
        if (billCycleDayLocal != that.billCycleDayLocal) {
            return false;
        }
        if (billingPeriod != that.billingPeriod) {
            return false;
        }
        if (currency != that.currency) {
            return false;
        }
        if (fixedPrice != null ? !fixedPrice.equals(that.fixedPrice) : that.fixedPrice != null) {
            return false;
        }
        if (description != null ? !description.equals(that.description) : that.description != null) {
            return false;
        }
        if (effectiveDate != null ? !effectiveDate.equals(that.effectiveDate) : that.effectiveDate != null) {
            return false;
        }
        if (plan != null ? !plan.equals(that.plan) : that.plan != null) {
            return false;
        }
        if (planPhase != null ? !planPhase.equals(that.planPhase) : that.planPhase != null) {
            return false;
        }
        if (totalOrdering != null ? !totalOrdering.equals(that.totalOrdering) : that.totalOrdering != null) {
            return false;
        }
        if (type != that.type) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = 31 * billCycleDayLocal;
        result = 31 * result + (subscriptionId != null ? subscriptionId.hashCode() : 0);
        result = 31 * result + (bundleId != null ? bundleId.hashCode() : 0);
        result = 31 * result + (fixedPrice != null ? fixedPrice.hashCode() : 0);
        result = 31 * result + (effectiveDate != null ? effectiveDate.hashCode() : 0);
        result = 31 * result + (planPhase != null ? planPhase.hashCode() : 0);
        result = 31 * result + (plan != null ? plan.hashCode() : 0);
        result = 31 * result + (currency != null ? currency.hashCode() : 0);
        result = 31 * result + (description != null ? description.hashCode() : 0);
        result = 31 * result + (billingPeriod != null ? billingPeriod.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (totalOrdering != null ? totalOrdering.hashCode() : 0);
        return result;
    }

    private BigDecimal computeFixedPrice() throws CatalogApiException {
        if (isCancelledOrBlocked ||
            type == SubscriptionBaseTransitionType.BCD_CHANGE /* We don't want to bill twice for the same fixed price */) {
            return null;
        }
        return (planPhase.getFixed() != null && planPhase.getFixed().getPrice() != null) ? planPhase.getFixed().getPrice().getPrice(currency) : null;
    }

    private BigDecimal computeRecurringPrice() throws CatalogApiException {
        if (isCancelledOrBlocked) {
            return null;
        }
        return (planPhase.getRecurring() != null && planPhase.getRecurring().getRecurringPrice() != null) ? planPhase.getRecurring().getRecurringPrice().getPrice(currency) : null;
    }

    private BillingPeriod computeRecurringBillingPeriod() {
        return planPhase.getRecurring() != null ? planPhase.getRecurring().getBillingPeriod() : BillingPeriod.NO_BILLING_PERIOD;
    }

    private List<Usage> computeUsages() {
        List<Usage> result = ImmutableList.<Usage>of();
        if (isCancelledOrBlocked) {
            return result;
        }
        if (planPhase.getUsages().length > 0) {
            result = Lists.newArrayList();
            for (Usage usage : planPhase.getUsages()) {
                result.add(usage);
            }
        }
        return result;
    }

    @Override
    public DateTime getCatalogEffectiveDate() {
        // TODO Can that go ?
        return catalogEffectiveDate;
    }
}
