## 0.1.20 - 12 Aug 2015

Added support for CSS Compression (using YUICompressor).
This is normally enabled only in production mode.

Revised the approach to caching significantly. 
File-system caching is now enabled for both production and development, but uses
different sub-directories of the specified root cache folder.

There have been some non-backwards compatible changes to several functions as a result.
 
In the Twixt options may, the :cache-folder key has been renamed to :cache-dir, and moved
under a new key, :cache.

The new caching and locking approach helps ensure that multiple threads do no attempt
to perform the same compilation steps in parallel.
There is now a per-asset lock used to prevent duplicate conflicting work across
multiple threads.

## 0.1.19 - 7 Aug 2015

Fixed bug where compressed assets would cause a double exception (an exception, caused by
an exception building the exception report).

## 0.1.18 - 31 Jul 2015

Reverted Clojure compatibility to 1.6.0.

## 0.1.17 - 29 Jul 2015

Updated dependencies, including Clojure to 1.7.0, and CoffeeScript compiler to 1.9.3.

Clarified many APIs by introducing Prismatic Schema signatures.

Added support for finding assets inside [WebJars](http://www.webjars.org/).

Twixt can now export assets to specific locations (typically, under public/resources).
Exports are live: monitored for changes and dynamically re-exported as needed.
This is intended largely to allow Less stylesheets to live-update when using
[Figwheel](https://github.com/bhauman/lein-figwheel).

## 0.1.16 - 10 Jun 2015

Update dependencies to latest.

## 0.1.15 - 7 Nov 2014

Added medley (0.5.3) as a dependency.
 
## 0.1.14 - Oct 24 2014

* In-line source maps for compiled Less files (in development mode)
* Update Less4J dependency to 0.8.3

No closed issues

## 0.1.13 - 6 Jun 2014

* Source maps for CoffeeScript
* Source maps for Less (partial, pending improvements to Less4J)
* Support for stacks: aggregated assets that combine into a single virtual asset
* JavaScript Minification (via Google Closure) 

[Closed Issues](https://github.com/AvisoNovate/twixt/issues?q=milestone%3A0.1.13)

## 0.1.12 - 29 Apr 2014

* Minor bug fixes; some relative paths computed incorrectly

[Closed Issues](https://github.com/AvisoNovate/twixt/issues?q=milestone%3A0.1.12)

## 0.1.11 - 13 Mar 2014

* Adds support for Jade helpers and variables

[Closed Issues](https://github.com/AvisoNovate/twixt/issues?q=milestone%3A0.1.11)

## 0.1.10 - 7 Mar 2014

* Jade `include` directive supported, with dependency tracking

[Closed Issues](https://github.com/AvisoNovate/twixt/issues?q=milestone%3A0.1.10)

## 0.1.9 - 26 Feb 2014

* Exception report displays map keys in sorted order
* Exception report displays some system properties as lists (e.g., `java.class.path`)

[Closed Issues](https://github.com/AvisoNovate/twixt/issues?q=milestone%3A0.1.9)