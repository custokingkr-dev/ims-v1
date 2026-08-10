param(
  [ValidateRange(1, 1000)] [int] $SchoolCount = 100,
  [ValidateRange(1, 10000000)] [int] $StudentCount = 200000,
  [ValidateRange(0, 100)] [decimal] $UtilityMessagesPerStudentMonth = 0,
  [ValidateRange(0, 100)] [decimal] $AuthenticationMessagesPerStudentMonth = 0,
  [ValidateRange(0, 100)] [decimal] $MarketingMessagesPerStudentMonth = 0,
  [ValidateRange(1, 1000)] [int] $WhatsappNumberCount = 1,
  [ValidateRange(0, 100000)] [decimal] $WhatsappNumberMonthlyInr = 500,
  [ValidateRange(0, 100)] [decimal] $WhatsappUtilityRateInr = 0.115,
  [ValidateRange(0, 100)] [decimal] $WhatsappAuthenticationRateInr = 0.115,
  [ValidateRange(0, 100)] [decimal] $WhatsappMarketingRateInr = 0.8631,
  [ValidateRange(0, 100000000)] [long] $SmsMessagesPerMonth = 0,
  [ValidateRange(0, 100)] [decimal] $SmsRateInr = 0.18,
  [ValidateRange(0, 100)] [decimal] $TaxPercent = 0
)

$ErrorActionPreference = 'Stop'

# MSG91 public India rate snapshot checked 2026-08-11. Parameter defaults are for planning,
# not a contractual quote. Override every rate after the provider invoice/rate card is approved.

$utilityMessages = [decimal]$StudentCount * $UtilityMessagesPerStudentMonth
$authenticationMessages = [decimal]$StudentCount * $AuthenticationMessagesPerStudentMonth
$marketingMessages = [decimal]$StudentCount * $MarketingMessagesPerStudentMonth

$numberSubscription = [decimal]$WhatsappNumberCount * $whatsappNumberMonthlyInr
$utilityCost = $utilityMessages * $whatsappUtilityRateInr
$authenticationCost = $authenticationMessages * $whatsappAuthenticationRateInr
$marketingCost = $marketingMessages * $whatsappMarketingRateInr
$smsCost = [decimal]$SmsMessagesPerMonth * $SmsRateInr
$subtotal = $numberSubscription + $utilityCost + $authenticationCost + $marketingCost + $smsCost
$tax = $subtotal * ($TaxPercent / 100)
$total = $subtotal + $tax

$schoolOwnedNumberSteadyState = ([decimal]$SchoolCount * $whatsappNumberMonthlyInr) +
  $utilityCost + $authenticationCost + $marketingCost + $smsCost

[pscustomobject]@{
  RateSnapshotDate = '2026-08-11'
  Schools = $SchoolCount
  Students = $StudentCount
  WhatsappNumbers = $WhatsappNumberCount
  UtilityMessages = [long][Math]::Ceiling($utilityMessages)
  AuthenticationMessages = [long][Math]::Ceiling($authenticationMessages)
  MarketingMessages = [long][Math]::Ceiling($marketingMessages)
  SmsMessages = $SmsMessagesPerMonth
  WhatsappNumberSubscriptionInr = [Math]::Round($numberSubscription, 2)
  WhatsappUtilityInr = [Math]::Round($utilityCost, 2)
  WhatsappAuthenticationInr = [Math]::Round($authenticationCost, 2)
  WhatsappMarketingInr = [Math]::Round($marketingCost, 2)
  SmsInr = [Math]::Round($smsCost, 2)
  PreTaxMonthlyInr = [Math]::Round($subtotal, 2)
  TaxPercent = $TaxPercent
  EstimatedMonthlyInr = [Math]::Round($total, 2)
  PerStudentMonthlyInr = [Math]::Round(($total / [decimal]$StudentCount), 4)
  SharedSenderPerSchoolMonthlyInr = [Math]::Round(($total / [decimal]$SchoolCount), 2)
  SchoolOwnedNumberSteadyStateInr = [Math]::Round($schoolOwnedNumberSteadyState * (1 + $TaxPercent / 100), 2)
  Assumptions = 'Steady-state rate card; excludes first-two-month number discounts, negotiated rates, retries, free service windows, wallet rules, GST unless TaxPercent is supplied, and email/support products.'
  Source = 'https://msg91.com/help/whatsapp/whatsapp-pricing- and https://msg91.com/in/pricing/sms'
} | Format-List
