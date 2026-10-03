import React, { useState } from 'react';
import { ANDROID_FILES, generateProjectZip, AndroidFileRecord } from '../lib/androidProjectZip';
import { FileCode, Download, Copy, Check, FolderTree, Cpu, ShieldCheck } from 'lucide-react';

export const CodeViewer: React.FC = () => {
  const [selectedFile, setSelectedFile] = useState<AndroidFileRecord>(ANDROID_FILES[0]);
  const [copied, setCopied] = useState<boolean>(false);
  const [isZipping, setIsZipping] = useState<boolean>(false);
  const [activeCategory, setActiveCategory] = useState<string>('All');

  const categories = ['All', 'Kotlin', 'Test', 'Config', 'Resource', 'Asset'];

  const filteredFiles =
    activeCategory === 'All'
      ? ANDROID_FILES
      : ANDROID_FILES.filter((f) => f.category === activeCategory);

  const handleCopy = () => {
    navigator.clipboard.writeText(selectedFile.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownloadZip = async () => {
    try {
      setIsZipping(true);
      const blob = await generateProjectZip();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'AWAZ-Android-Native-Project.zip';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      console.error('Failed to generate zip:', err);
    } finally {
      setIsZipping(false);
    }
  };

  return (
    <div className="space-y-4 text-left">
      {/* Top Banner & ZIP Download */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-lg flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h3 className="text-base font-bold text-white flex items-center gap-2">
            <Cpu className="w-5 h-5 text-blue-400" />
            Complete Native Android Kotlin Codebase
          </h3>
          <p className="text-xs text-slate-400 mt-1">
            Zero placeholders, production-ready Gradle structure, multi-flavor build config (standard & a11yTool), and JUnit tests.
          </p>
        </div>

        <button
          onClick={handleDownloadZip}
          disabled={isZipping}
          className="px-4 py-2.5 bg-blue-600 hover:bg-blue-500 active:scale-95 text-white text-xs font-bold rounded-xl flex items-center gap-2 shadow-lg shadow-blue-600/30 transition shrink-0 cursor-pointer"
        >
          <Download className="w-4 h-4" />
          {isZipping ? 'Packaging ZIP...' : 'Download Android Studio Project (.ZIP)'}
        </button>
      </div>

      {/* Main File Browser */}
      <div className="bg-slate-900 border border-slate-800 rounded-2xl overflow-hidden shadow-lg grid grid-cols-1 md:grid-cols-12 min-h-[580px]">
        {/* Left File Navigation Pane */}
        <div className="md:col-span-4 border-r border-slate-800 bg-slate-950/60 p-3 flex flex-col">
          {/* Category Filter Pills */}
          <div className="flex flex-wrap gap-1 mb-3">
            {categories.map((cat) => (
              <button
                key={cat}
                onClick={() => setActiveCategory(cat)}
                className={`px-2 py-1 rounded text-[11px] font-medium transition cursor-pointer ${
                  activeCategory === cat
                    ? 'bg-blue-600 text-white'
                    : 'bg-slate-800 text-slate-400 hover:bg-slate-700 hover:text-white'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          <div className="text-[10px] uppercase font-bold text-slate-400 tracking-wider px-2 mb-2 flex items-center gap-1.5">
            <FolderTree className="w-3.5 h-3.5 text-slate-400" />
            Project Files ({filteredFiles.length})
          </div>

          {/* Files List */}
          <div className="flex-1 overflow-y-auto space-y-1 pr-1 max-h-[500px]">
            {filteredFiles.map((file) => {
              const fileName = file.path.split('/').pop();
              const isSelected = selectedFile.path === file.path;
              return (
                <button
                  key={file.path}
                  onClick={() => setSelectedFile(file)}
                  className={`w-full text-left px-2.5 py-2 rounded-lg text-xs font-mono transition flex items-center justify-between cursor-pointer ${
                    isSelected
                      ? 'bg-blue-600/20 text-blue-300 border border-blue-500/40 font-semibold'
                      : 'text-slate-300 hover:bg-slate-800/60 hover:text-white'
                  }`}
                >
                  <div className="flex items-center gap-2 truncate">
                    <FileCode className={`w-3.5 h-3.5 shrink-0 ${isSelected ? 'text-blue-400' : 'text-slate-500'}`} />
                    <span className="truncate">{fileName}</span>
                  </div>
                  <span
                    className={`text-[9px] px-1.5 py-0.5 rounded uppercase shrink-0 font-sans ${
                      file.category === 'Kotlin'
                        ? 'bg-purple-950/80 text-purple-300 border border-purple-800/40'
                        : file.category === 'Test'
                        ? 'bg-emerald-950/80 text-emerald-300 border border-emerald-800/40'
                        : 'bg-slate-800 text-slate-400'
                    }`}
                  >
                    {file.category}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* Right Code Display Pane */}
        <div className="md:col-span-8 flex flex-col bg-slate-950">
          {/* Header */}
          <div className="bg-slate-900/90 px-4 py-3 border-b border-slate-800 flex items-center justify-between">
            <div className="flex items-center gap-2 min-w-0">
              <span className="text-xs font-mono text-cyan-300 truncate">
                {selectedFile.path}
              </span>
            </div>

            <button
              onClick={handleCopy}
              className="px-2.5 py-1 bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs rounded flex items-center gap-1.5 transition cursor-pointer"
            >
              {copied ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-400" />
                  <span className="text-emerald-400 font-medium">Copied</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" />
                  <span>Copy</span>
                </>
              )}
            </button>
          </div>

          {/* Code Body */}
          <div className="flex-1 p-4 overflow-x-auto overflow-y-auto max-h-[520px]">
            <pre className="text-xs font-mono text-slate-300 leading-relaxed">
              <code>{selectedFile.content}</code>
            </pre>
          </div>
        </div>
      </div>
    </div>
  );
};
