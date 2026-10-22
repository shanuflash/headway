// Ways to top up a Singara Chennai / NCMC card. Only links that were checked are included;
// providers without a verifiable link carry steps instead. Keep in sync with Android's TopUp.kt.
export const TOP_UP = [
  {
    id: 'airtel',
    name: 'Airtel Payments Bank',
    detail: "The card top-up screen in Airtel Thanks, which the app's menus don't show.",
    fee: 'Free',
    balance: 'Can write the new balance to the card from your phone',
    // The short link redirects to myairtel://apb_cards?card=ncmc when the app is installed.
    link: 'https://i.airtel.in/AIRBNK/transit-card',
  },
  {
    id: 'sbi',
    name: 'SBI Singara Chennai card',
    detail: "SBI's prepaid card portal.",
    fee: 'Free (SBI lists no reload fee)',
    balance: 'Update the balance at a Metro station before you travel',
    link: 'https://prepaid.sbi.bank.in',
  },
  {
    id: 'airtel-bbps',
    name: 'Airtel Thanks, NCMC recharge',
    detail: "The usual recharge in the app's menus, through BBPS.",
    fee: '₹1.18',
    balance: 'Update the balance at a Metro station before you travel',
  },
  {
    id: 'bbps',
    name: 'PhonePe, BHIM UPI or SBI Unipay',
    detail: "Recharge & bills, then NCMC recharge, then your card's bank (BBPS).",
    fee: 'Set by the app; check before paying',
    balance: 'Update the balance at a Metro station before you travel',
  },
  {
    id: 'station',
    name: 'Metro station counter',
    detail: 'Cash, UPI or card at any CMRL ticket counter.',
    fee: 'Free',
    balance: 'Loaded on the card right away',
  },
];
