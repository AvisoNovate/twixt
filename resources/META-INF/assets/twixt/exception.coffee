# Adds a simple event handler that toggles body.hide-filtered when the toggle filter button is clicked.

$ ->
  $("body").on "click", "[data-action=toggle-filter]", ->
    $("body").toggleClass "hide-filtered"