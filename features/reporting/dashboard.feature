Feature: An operator reads the dashboard
  An operator opens the dashboard and sees the brand now: players online, active games, today's
  registrations, deposits, withdrawals, GGR and NGR against yesterday, the seven-day history, the
  trend over a chosen range, players by country, the top games and players, today's notable
  activity, the open restricted funds liability, and the payment providers' health. Every figure
  is the brand's, not a sample.

  Background:
    Given brand "goldvibe"

  Scenario: today's money figures are the sums over every player, and yesterday's and the trend arrow are computed the same way
    Given more than twenty players with activity today
    When the operator reads the dashboard
    Then GGR, NGR, deposits and withdrawals today are the sums over every player
    And yesterday's figures and the trend arrow are computed the same way

  Scenario: players online counts open player sessions and active games counts open game sessions
    Given players online and games in play
    When the operator reads the dashboard
    Then players online counts the open player sessions
    And active games counts the open game sessions

  Scenario: payment health shows the day's deposit success rate per provider and the average time to a withdrawal's completion, with no rate for a day with no attempts
    Given the day's deposit attempts and outcomes
    When the operator reads payment health
    Then the deposit success rate is completed deposits over attempts for the day, per payment provider
    And the average time to a withdrawal's completion is over the day's completed withdrawals
    And a day with no attempts shows no rate, never a constant

  Scenario Outline: the trend over a range holds the GGR and NGR of each span in the brand's zone
    Given the trend asked for <range>
    When it is read
    Then it is divided into <unit>, each holding the GGR and NGR of its span in the brand's reporting zone

    Examples:
      | range         | unit  |
      | today         | hours |
      | seven days    | days  |
      | thirty days   | days  |
      | ninety days   | weeks |

  Scenario: the rankings for a range order games by game sessions started and players by GGR, with country and total wagered
    Given the rankings for a range
    When they are read
    Then games are ranked by game sessions started in the range
    And players are ranked by GGR in the range, with the player's country and total wagered

  Scenario: today's registrations, awards, self-exclusions and suspensions appear in the activity list newest first
    Given today's registrations, awards, self-exclusions and suspensions
    When the activity list is read
    Then each appears as one entry with its time and the player, newest first

  Scenario: the bonus summary shows the open records, their restricted balance as the liability, the average progress and today's bonus cost
    Given open restricted funds records
    When the bonus summary is read
    Then it shows the count of open records, their total restricted balance as the brand's liability, the average release progress and today's bonus cost

  Scenario: the geography counts every player of the brand by country
    Given players by country
    When the geography is read
    Then each country's count is over every player of the brand
