define ["dep1", "dep2"],
  (dep1, dep2) ->

    # Error is the missing comma after the string:
    dep1.doSomething "atrocious"
      argument: dep2
