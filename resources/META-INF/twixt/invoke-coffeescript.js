// Compiles CoffeeScript source to JavaScript.
//
// input - string containing contents of the file
// filepath - name of file, used to report errors
// filename - last term in filepath
//
// Returns { output: <compiled JavaScript>, sourceMap: source-map-as-string } or { exception: <exception message> }
function compileCoffeeScriptSource(input, filepath, filename) {
    try {
        var result = CoffeeScript.compile(input, {
            header: true,
            filename: filepath,
            sourceFiles: [filename],
            sourceMap: true,
            inline: true});

        return {
            output: result.js + "\n//# sourceMappingURL=" + filename + "@source.map\n" ,
            sourceMap: result.v3SourceMap
        };
    }
    catch (err) {
        return { exception: err.toString() };
    }
}
