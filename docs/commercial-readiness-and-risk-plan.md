# Commercial Readiness And Risk Plan

## Purpose

This document describes how to move YokhanVPN from a technical prototype into a small commercial product for a private circle of trusted users.

The target model is not a mass-market public VPN. The practical wedge is a concierge, invite-only product: family, friends, small teams, and known users who need a stable private connectivity tool with human support.

This is not legal advice. Before public sales, App Store distribution, Google Play distribution, or Russian-market activity, the owner should get jurisdiction-specific legal review.

## Non-Negotiable Boundary

Do not disguise a VPN as a game, toy, or unrelated app to avoid law, store review, payment-provider review, or user consent.

Rejected approach:

1. Publish a "rocket game" that secretly downloads or activates a VPN module.
2. Sell keys from a landing page that intentionally avoids describing the product's real network function.
3. Use euphemistic copy to hide that the product creates encrypted network tunnels.
4. Submit an app to stores as one category while the real paid feature is a VPN/proxy tunnel.

Why this is rejected:

1. It creates app-store fraud risk.
2. It creates user-trust risk.
3. It increases legal exposure instead of reducing it.
4. It makes payment-provider and platform bans more likely.
5. It makes the product harder to defend if challenged.

Accepted approach:

1. Use the rocket theme as brand/UI metaphor only.
2. Keep product descriptions truthful: "private access", "secure tunnel", "protected remote access", "managed connectivity", "private network access".
3. Avoid public claims about bypassing restricted resources.
4. Keep distribution invite-only until compliance is ready.
5. Use modular architecture for maintainability and platform separation, not for concealment.

## Positioning

The strongest early positioning is:

> Private managed access for a small trusted circle.

Practical promise:

1. The user does not need to understand protocols.
2. The app connects reliably.
3. Setup is done with clear onboarding or direct support.
4. The owner can rotate keys, revoke access, and help users.
5. The product avoids intrusive analytics and does not sell traffic data.

Avoid these claims:

1. "Unblocks everything."
2. "Bypasses any restriction."
3. "Anonymous internet."
4. "No one can ever see anything."
5. "Guaranteed legal everywhere."

Safer claims:

1. "Private managed connectivity."
2. "Protected remote access for trusted users."
3. "Simple client for personal and family network profiles."
4. "Encrypted tunnel between your device and configured endpoints."
5. "Minimal data collection and clear support."

## Product Model

### Phase 1: Concierge Access

This is the best fit for the first sales.

Scope:

1. Invite-only users.
2. Manual onboarding.
3. Manual payment tracking.
4. Direct support through a private channel.
5. Android-first client.
6. Windows preview/debug client for UI work, not necessarily a commercial release yet.

Deliverables:

1. Signed Android release.
2. Simple onboarding screen.
3. Clear "no configurations" state.
4. One-page setup guide.
5. Private support contact.
6. Manual access registry.

Success metric:

1. A non-technical user can install, receive access, connect, disconnect, and ask for help without developer intervention after the initial invite.

### Phase 2: Small Circle Subscription

Scope:

1. Invite code or account code.
2. Self-service access page.
3. Subscription status.
4. Key rotation.
5. Device limit.
6. Renewal reminders.

Deliverables:

1. Landing page with truthful, non-evasive wording.
2. Payment flow.
3. Basic user/admin registry.
4. Access revoke/rotate tools.
5. Support SLA expectations.

Success metric:

1. The owner can manage 30-100 users without editing files by hand.

### Phase 3: Team/Family Plans

Scope:

1. Family owner or team owner.
2. Multiple devices.
3. Shared billing.
4. Access groups.
5. Simple admin panel.

Deliverables:

1. Group model.
2. Device inventory.
3. Invite/revoke flow.
4. Status page.
5. Support knowledge base.

Success metric:

1. One paying owner can manage several users with low support load.

## Distribution Strategy

### Android

Near-term:

1. Private APK distribution from GitHub Release or another controlled download page.
2. Clear install guide.
3. Signature verification instructions.
4. Versioned releases.
5. No public claim that the app is a game if it contains tunnel functionality.

Google Play path:

1. If the app uses Android `VpnService`, Google requires a VpnService declaration.
2. Google expects the Play listing to document VpnService use.
3. Google requires encryption from device to tunnel endpoint.
4. If VPN is not the core purpose or an allowed category, the app can be rejected.

Practical conclusion:

1. Do not submit a disguised app to Google Play.
2. If using Google Play, submit honestly as a private connectivity/security tool and prepare the declaration materials.
3. For "family and friends" scale, private distribution may be more realistic than store distribution.

### iOS

Near-term:

1. Do not assume iOS can mirror Android plugin architecture.
2. iOS VPN functionality requires Apple networking APIs/entitlements and organization-level expectations.
3. For family/friends, Apple itself suggests that the public App Store may not be the right path; Ad Hoc or similar private distribution can fit limited testing.

App Store path:

1. VPN apps must use Apple's VPN APIs.
2. VPN apps must disclose collected data before purchase/use.
3. VPN apps must not violate local laws.
4. If a territory requires a VPN license, Apple expects license information in review notes.
5. Apple explicitly warns against trying to trick the review process.

Practical conclusion:

1. Do not ship a "rocket game" that later activates hidden VPN behavior.
2. If iOS becomes commercial, prepare a clean organization account, privacy policy, review notes, and supported territory list.

### Windows

Near-term:

1. Windows is useful for fast UI QA and support tooling.
2. A production Windows client should be signed.
3. Distribution can be private at first.
4. The installer should clearly say what network components it installs or controls.

Practical conclusion:

1. Windows can become the admin/support companion first, then a commercial client later.

## Russia Risk Model

This section is deliberately conservative.

Known risk themes:

1. Russian authorities treat VPN services that provide access to restricted resources as regulatory targets.
2. Public promotion or sale of access to restriction-circumvention tools is a separate risk area.
3. Technical or promotional material about VPNs used for bypassing restrictions can itself be treated as problematic.
4. Storefronts can remove VPN apps from specific territories after regulator requests.

Do not do:

1. Do not market to Russia as a VPN for bypassing blocked resources.
2. Do not publish Russian-language instructions about bypassing restricted websites.
3. Do not sell public "VPN keys" from a Russian-facing landing page.
4. Do not claim that a hidden plugin architecture removes legal risk.
5. Do not use fake app descriptions or misleading store screenshots.

Lower-risk operating model:

1. Invite-only access for known users.
2. No public Russian-market advertising.
3. No public Russian-language landing page that sells tunnel access.
4. No SEO around VPN, bypass, блокировки, обход, anonymous access, or similar terms.
5. Access positioned and implemented as protected remote access for private users/teams.
6. Clear abuse policy and right to revoke access.
7. Minimal logs, but enough operational data to stop abuse and support users.
8. Legal review before accepting broad Russian-market payments.

Important caveat:

1. "Euphemistic" copy does not eliminate legal risk if the actual service and user intent are obvious.
2. Private distribution can reduce public exposure, but it is not immunity.
3. The safest commercial route may be to avoid public Russia targeting entirely.

## Landing Page Strategy

A safer landing page should explain benefits without promoting circumvention.

Recommended language:

1. "Private managed access for trusted users."
2. "Encrypted connectivity profiles for your devices."
3. "Human setup support."
4. "Family and small-team plans."
5. "Minimal data collection."

Avoid:

1. "VPN for Russia."
2. "Bypass blocks."
3. "Access any banned service."
4. "Anonymous and untraceable."
5. "Works despite all restrictions."

Required pages:

1. Privacy policy.
2. Terms of service.
3. Acceptable use policy.
4. Refund policy.
5. Support contact.
6. Security note: what the app can and cannot protect.

## Architecture Direction

Modularity is still useful, but it must be honest.

Recommended split:

1. `core`: domain models, subscriptions, profiles, access state.
2. `ui`: Compose shared UI.
3. `android-network`: Android tunnel integration.
4. `desktop-preview`: UI preview and QA tooling.
5. `admin-tools`: access registry, key rotation, diagnostics.
6. `distribution`: release scripts, signing, checksums, update metadata.

Avoid:

1. Downloading executable network modules after install to hide store-reviewed behavior.
2. Runtime plugin loading from unsigned sources.
3. Feature flags that make review builds materially different from user builds.

Allowed:

1. Build variants for platform capabilities.
2. Optional protocol providers if they are signed, versioned, documented, and user-approved.
3. Server-side profile delivery where the app clearly explains that profiles configure protected connectivity.

## Operational Requirements

Before scaling beyond friends:

1. Keep an access registry: user, contact, plan, device count, status, expiry.
2. Add revoke and rotate workflows.
3. Keep release notes per build.
4. Keep checksums for APK/installer downloads.
5. Track install success and connection success without collecting browsing data.
6. Define support channels and response expectations.
7. Have an incident plan for leaked keys, server outage, or abusive use.

Minimal telemetry:

1. App version.
2. Platform.
3. Install/update success.
4. Profile import success/failure.
5. Connection state transitions.
6. Error categories.

Do not collect:

1. Browsing history.
2. DNS query content unless explicitly necessary and disclosed.
3. Full destination logs for normal users.
4. Contact lists or unrelated device data.

## Pricing Experiments

Start simple.

Option A: Family plan

1. One owner.
2. 3-5 devices.
3. Human support.
4. Monthly or quarterly payment.

Option B: Personal managed access

1. One user.
2. 1-2 devices.
3. Setup help.
4. Lower price, lower support commitment.

Option C: Small team

1. 5-20 users.
2. Higher price.
3. Better support SLA.
4. Clear abuse and availability terms.

Do not overbuild billing until there are repeat renewals.

## Sales Process

Recommended first sales funnel:

1. Private invite.
2. Short explanation of what the product does.
3. Install guide.
4. Manual onboarding call/message.
5. Confirm first successful connection.
6. Ask what confused the user.
7. Record support issues.
8. Ask for renewal after a stable period.

The best early signal is not signups. It is users renewing after they had one real support issue and still trust the product.

## Immediate Next Steps

1. Finish installable signed Android release pipeline and make the latest artifact easy to download.
2. Add first-run onboarding copy in the app: no profile, how to get access, how to import, support contact.
3. Create private setup guide.
4. Draft privacy policy and terms.
5. Create a manual customer/access registry.
6. Add key revoke/rotate admin checklist.
7. Keep public language away from restriction-bypass claims.
8. Decide whether Russia is excluded from public targeting until legal review.
9. Keep desktop preview as the UI QA tool for fast fixes.
10. Make CI upload artifacts on every manual release build.

## External References

1. Google Play VpnService policy: https://support.google.com/googleplay/android-developer/answer/12564964
2. Apple App Store Review Guidelines, VPN apps and anti-deception guidance: https://developer.apple.com/app-store/review/guidelines/
3. Russian network-control risk context from the public network monitoring portal: https://portal.noc.gov.ru/ru/news/roskomnadzor-rekomenduet-kompaniyam-otkazatsya-ot-vpn-s-ispolzovaniem-zarubezhnyh-serverov/
4. GDPR and small-business data protection guidance: https://commission.europa.eu/law/law-topic/data-protection/data-protection-explained_en
5. EDPB small-business data protection guide: https://www.edpb.europa.eu/sme-data-protection-guide/home_en
