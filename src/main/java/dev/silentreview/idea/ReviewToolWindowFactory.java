package dev.silentreview.idea;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.content.ContentFactory;
import dev.silentreview.core.*;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public final class ReviewToolWindowFactory implements ToolWindowFactory,DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window){
        Panel panel=new Panel(project,window);
        var content=ContentFactory.getInstance().createContent(panel,"提交审查",false);
        content.setDisposer(panel);window.getContentManager().addContent(content);
    }
    private static final class Panel extends JPanel implements Disposable {
        private final Project project;
        private final ReviewService service;
        private final JLabel status=new JLabel("等待检查");
        private final DefaultListModel<Review.Report> model=new DefaultListModel<>();
        private final JList<Review.Report> history=new JList<>(model);
        private final JEditorPane body=new JEditorPane("text/html","");
        private final JComboBox<String> rootFilter=new JComboBox<>(new String[]{"全部仓库"});
        private final JComboBox<String> decisionFilter=new JComboBox<>(new String[]{"全部处理状态","待处理","已确认","已采纳","已关闭"});
        private final JComboBox<String> severityFilter=new JComboBox<>(new String[]{"全部严重级别","error","warning","info"});
        private final JTabbedPane tabs=new JTabbedPane();
        private final JLabel scopeCounts=new JLabel("尚未准备范围。先刷新范围，再选择文件并开始审查。");
        private final JTextArea scopeInfo=new JTextArea(4,30);
        private final ScopeTable scopeModel=new ScopeTable();
        private final JTable scopeTable=new JTable(scopeModel);
        private final List<JButton> scopeActions=new ArrayList<>();
        private final List<JButton> decisionActions=new ArrayList<>();
        private JButton start;
        private ReviewService.View view;
        private boolean refreshing;
        private volatile boolean disposed;
        Panel(Project project,ToolWindow window){
            super(new BorderLayout(8,8));this.project=project;service=ReviewService.get(project);view=service.view();
            JPanel toolbar=new JPanel(new GridLayout(0,3,5,5));
            scopeActions.add(button(toolbar,"准备当前提交",()->service.manual(null)));
            scopeActions.add(button(toolbar,"准备分支范围",()->{
                String reference=Messages.showInputDialog(project,"输入公共分支引用，或 @{upstream} 使用各仓库的本地上游引用。不会自动更新远端引用。","选择检查基线",null);
                if(reference!=null&&!reference.isBlank())service.manual(reference.strip());
            }));
            button(toolbar,"取消当前",service::cancel);
            button(toolbar,"设置",()->ShowSettingsUtil.getInstance().showSettingsDialog(project,ReviewConfigurable.class));
            button(toolbar,"显示大肥鱼",()->{ReviewSettings.get(project).data().fishVisible=true;service.settingsChanged();});
            button(toolbar,"收起面板",()->window.hide(null));
            JPanel top=new JPanel(new BorderLayout());top.add(toolbar,BorderLayout.NORTH);top.add(status,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
            tabs.addTab("审查范围",scopePanel());tabs.addTab("报告与处理",reportPanel());add(tabs,BorderLayout.CENTER);
            service.listen(this::refresh,this);service.startFish();
        }
        private JPanel scopePanel(){
            JPanel panel=new JPanel(new BorderLayout(5,5));
            JPanel actions=new JPanel(new GridLayout(0,3,5,5));
            scopeActions.add(button(actions,"刷新范围",()->service.prepareScope(view.scope().baseline())));
            scopeActions.add(button(actions,"全部纳入",()->service.setAllIncluded(true)));
            scopeActions.add(button(actions,"全部排除",()->service.setAllIncluded(false)));
            start=button(actions,"开始审查已纳入文件",service::startPreparedReview);
            button(actions,"查看选中文件 Diff",this::scopeDiff);
            JPanel heading=new JPanel(new BorderLayout());heading.add(actions,BorderLayout.NORTH);heading.add(scopeCounts,BorderLayout.CENTER);
            JLabel hint=new JLabel("<html>勾选仅控制插件审查范围。<br>IDE 提交窗口的 included 数量不代表本插件的审查数量。</html>");
            heading.add(hint,BorderLayout.SOUTH);panel.add(heading,BorderLayout.NORTH);
            scopeTable.setAutoCreateRowSorter(true);scopeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            scopeTable.getColumnModel().getColumn(0).setMaxWidth(64);
            scopeTable.getColumnModel().getColumn(0).setCellRenderer((table,value,selected,focus,row,column)->{
                JCheckBox checkbox=new JCheckBox();checkbox.setHorizontalAlignment(SwingConstants.CENTER);checkbox.setOpaque(true);
                checkbox.setSelected(Boolean.TRUE.equals(value));checkbox.setEnabled(scopeModel.isCellEditable(table.convertRowIndexToModel(row),0));
                checkbox.setBackground(selected?table.getSelectionBackground():table.getBackground());
                checkbox.setToolTipText(scopeModel.rows.get(table.convertRowIndexToModel(row)).unavailableReason());return checkbox;
            });
            scopeTable.setRowHeight(Math.max(22,scopeTable.getRowHeight()));
            panel.add(new JScrollPane(scopeTable),BorderLayout.CENTER);
            scopeInfo.setEditable(false);scopeInfo.setLineWrap(true);scopeInfo.setWrapStyleWord(true);
            panel.add(new JScrollPane(scopeInfo),BorderLayout.SOUTH);return panel;
        }
        private JPanel reportPanel(){
            JPanel panel=new JPanel(new BorderLayout(5,5));
            JPanel actions=new JPanel(new GridLayout(0,3,5,5));
            JPanel filters=new JPanel(new GridLayout(0,1,5,5));
            filters.add(rootFilter);filters.add(decisionFilter);filters.add(severityFilter);
            rootFilter.setRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                    if(value instanceof String root){label.setText(root.equals("全部仓库")?root:rootName(root));label.setToolTipText(root);}return label;
                }
            });
            rootFilter.addActionListener(e->applyFilters());decisionFilter.addActionListener(e->applyFilters());severityFilter.addActionListener(e->applyFilters());
            decisionActions.add(button(actions,"确认报告",()->acknowledge(Reports.Decision.CONFIRMED)));
            decisionActions.add(button(actions,"采纳建议",()->acknowledge(Reports.Decision.ACCEPTED)));
            decisionActions.add(button(actions,"关闭报告",()->acknowledge(Reports.Decision.CLOSED)));
            decisionActions.add(button(actions,"重新待处理",()->acknowledge(Reports.Decision.UNREVIEWED)));
            button(actions,"导出所选报告",this::export);
            button(actions,"清理历史",()->{if(Messages.showYesNoDialog(project,"删除本项目的本地报告和人工处理历史？","清理报告",null)==Messages.YES)service.clearHistory();});
            JPanel heading=new JPanel(new BorderLayout(5,5));heading.add(filters,BorderLayout.NORTH);heading.add(actions,BorderLayout.CENTER);panel.add(heading,BorderLayout.NORTH);
            history.setCellRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                    if(value instanceof Review.Report r){
                        label.setText(rootName(r.root())+" · "+shortRevision(r.target())+" · "+Reports.label(Reports.resolution(r.id(),view.resolutions()).decision())+" · "+Reports.label(r.result().status())+" · "+r.result().findings().size()+" 项");
                        label.setToolTipText(r.root()+" @ "+r.target());
                    }return label;
                }
            });
            history.setPreferredSize(new Dimension(270,300));history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            history.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!refreshing)render();});
            body.setEditable(false);body.addHyperlinkListener(e->{
                if(e.getEventType()==HyperlinkEvent.EventType.ACTIVATED&&e.getDescription()!=null){
                    String link=e.getDescription();
                    try{
                        if(link.startsWith("finding:"))locate(Integer.parseInt(link.substring(8)));
                        else if(link.startsWith("snapshot:"))snapshot(Integer.parseInt(link.substring(9)));
                    }catch(NumberFormatException ignored){}
                }
            });
            JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(history),new JScrollPane(body));split.setResizeWeight(0.28);panel.add(split,BorderLayout.CENTER);
            return panel;
        }
        private static JButton button(JPanel parent,String title,Runnable action){JButton b=new JButton(title);b.addActionListener(e->action.run());parent.add(b);return b;}
        private void refresh(ReviewService.View next){
            if(disposed)return;
            if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->refresh(next));return;}
            String newestBefore=view.reports().isEmpty()?null:view.reports().get(0).id();view=next;
            status.setText(Reports.label(next.status())+"："+next.message());status.setToolTipText(next.message());
            scopeActions.forEach(b->b.setEnabled(!next.busy()&&!next.scope().preparing()));
            start.setEnabled(!next.busy()&&next.scope().ready());
            scopeModel.rows=next.scope().repositories().stream().flatMap(r->r.files().stream()).toList();scopeModel.fireTableDataChanged();
            var s=next.scope();
            scopeCounts.setText("<html>共 "+s.repositories().size()+" 个仓库 / "+s.fileCount()+" 个变更文件；<br>已纳入 "+s.includedCount()+"，已排除 "+s.excludedCount()+"，可选 "+s.eligibleCount()+"，失败仓库 "+s.failedRepositoryCount()+(s.preparing()?" · 准备中":s.stale()?" · 范围已过期，请刷新":"")+"</html>");
            StringBuilder info=new StringBuilder("审查与 Diff 都使用此处冻结的提交；不含尚未提交的编辑。\n");
            next.discoveryNotes().forEach(note->info.append(note).append('\n'));
            next.discoveryLimitations().forEach(warning->info.append("发现提示：").append(warning).append('\n'));
            for(var repo:s.repositories())info.append(repo.root()).append("\n  ").append(repo.branch()).append(" · HEAD ").append(repo.head()).append(" · 基线 ").append(repo.baseline()).append(" · ").append(repo.description()).append(repo.error()==null||repo.error().isBlank()?"":" · 失败："+repo.error()).append('\n');
            scopeInfo.setText(info.toString());scopeInfo.setCaretPosition(0);
            refreshing=true;String oldRoot=(String)rootFilter.getSelectedItem();rootFilter.removeAllItems();rootFilter.addItem("全部仓库");
            next.reports().stream().map(Review.Report::root).distinct().sorted().forEach(rootFilter::addItem);
            if(oldRoot!=null)rootFilter.setSelectedItem(oldRoot);if(rootFilter.getSelectedIndex()<0)rootFilter.setSelectedIndex(0);refreshing=false;
            applyFilters();
            String newest=next.reports().isEmpty()?null:next.reports().get(0).id();
            if(newest!=null&&!Objects.equals(newest,newestBefore)){tabs.setSelectedIndex(1);if(model.size()>0)history.setSelectedIndex(0);}
            else if(next.scope().preparing())tabs.setSelectedIndex(0);
        }
        private void applyFilters(){
            if(refreshing)return;
            Review.Report selected=history.getSelectedValue();String id=selected==null?null:selected.id();
            Reports.Decision decision=decisionFilter.getSelectedIndex()<=0?null:Reports.Decision.values()[decisionFilter.getSelectedIndex()-1];
            String root=rootFilter.getSelectedIndex()<=0?null:(String)rootFilter.getSelectedItem();String severity=selectedSeverity();
            refreshing=true;model.clear();ReportFilters.select(view.reports(),view.resolutions(),root,decision,severity).forEach(model::addElement);
            int chosen=0;for(int i=0;i<model.size();i++)if(model.get(i).id().equals(id))chosen=i;
            if(!model.isEmpty())history.setSelectedIndex(chosen);refreshing=false;render();
        }
        private String selectedSeverity(){return severityFilter.getSelectedIndex()<=0?null:(String)severityFilter.getSelectedItem();}
        private void render(){
            Review.Report r=history.getSelectedValue();decisionActions.forEach(b->b.setEnabled(r!=null&&!view.busy()));
            if(r==null){body.setText("<html><body><h2>静默代码检测</h2><p>在设置中创建默认规则模板并配置 AI 接口，然后准备范围、纳入文件并开始审查。</p><p>报告会按仓库独立保存。已关闭报告仍可通过处理状态筛选查看。</p><p>当前筛选下没有报告。</p></body></html>");return;}
            Reports.Resolution resolution=Reports.resolution(r.id(),view.resolutions());
            StringBuilder html=new StringBuilder("<html><body style='font-family:sans-serif;margin:16px'><h2>提交分析报告</h2>");
            html.append("<h3>仓库：").append(escape(r.root())).append("</h3>");
            paragraph(html,"AI 执行状态",Reports.label(r.result().status()));paragraph(html,"人工处理状态",Reports.label(resolution.decision()));
            html.append("<p><b>人工确认、采纳、关闭仅记录处理进度，不改变 AI 执行结果、覆盖状态或未完成项。采纳不会自动修改代码。</b></p>");
            if(r.result().status()!=Review.Status.COMPLETED)html.append("<p><b>本报告未获得完整成功的 AI 检查结果，请结合限制与覆盖记录处理。</b></p>");
            paragraph(html,"时间",r.createdAt());paragraph(html,"分支",r.branch());paragraph(html,"范围",r.scope());paragraph(html,"基线",r.base());paragraph(html,"提交",r.target());
            paragraph(html,"规则集",r.ruleSetName()+" / "+r.ruleSetVersion());paragraph(html,"指纹",r.fingerprint());paragraph(html,"模型",r.model());
            html.append("<h3>人工处理历史</h3>");
            if(resolution.history().isEmpty())html.append("<p>尚未处理。使用上方确认、采纳或关闭按钮记录处理结果。</p>");
            for(Reports.AuditEvent event:resolution.history())paragraph(html,event.at()+" · "+Reports.label(event.decision())+" · "+event.actor(),event.note().isBlank()?"无备注":event.note());
            if(r.result().status()==Review.Status.COMPLETED&&r.result().findings().isEmpty())html.append("<p>本次检查范围内未发现问题。</p>");
            String severity=selectedSeverity();
            for(int i=0;i<r.result().findings().size();i++){
                Review.Finding f=r.result().findings().get(i);if(severity!=null&&!severity.equals(f.severity()))continue;
                html.append("<h3>").append(escape(f.ruleId()+" · "+f.severity()+" · "+f.kind())).append("</h3><p>")
                    .append(escape(rootName(r.root())+" / "+f.filePath()+":"+f.startLine())).append("<br><a href='finding:").append(i).append("'>打开项目源码</a> · <a href='snapshot:").append(i).append("'>查看提交快照（")
                    .append(escape(shortRevision(f.side().equals("before")?r.base():r.target()))).append("）</a></p><pre>").append(escape(f.evidence())).append("</pre>");
                paragraph(html,"原因",f.explanation());paragraph(html,"建议",f.suggestion());paragraph(html,"与变更的关系",f.changeRelation());
                if(r.ruleDefinitions()!=null)r.ruleDefinitions().stream().filter(rule->f.ruleId().equals(rule.get("id").getAsString())).findFirst().ifPresent(rule->{
                    paragraph(html,"规则要求",rule.get("requirement").getAsString());
                    if(rule.has("source")&&rule.getAsJsonObject("source").has("excerpt"))paragraph(html,"规范依据",rule.getAsJsonObject("source").get("excerpt").getAsString());
                });
            }
            html.append("<h3>检查覆盖</h3>");
            for(Review.Coverage c:r.result().coverage())paragraph(html,c.filePath()+" / "+c.ruleId(),c.status()+" "+c.reason());
            if(!r.result().limitations().isEmpty()){html.append("<h3>限制与未完成项</h3>");r.result().limitations().forEach(l->html.append("<p>").append(escape(l)).append("</p>"));}
            html.append("</body></html>");body.setText(html.toString());body.setCaretPosition(0);
        }
        private void acknowledge(Reports.Decision decision){
            Review.Report r=history.getSelectedValue();if(r==null)return;
            String note=Messages.showInputDialog(project,"为报告记录“"+Reports.label(decision)+"”。备注可留空。\n这不会改变 AI 执行状态，也不会修改代码。","处理报告",null,"",null);
            if(note!=null)service.acknowledge(r.id(),decision,note);
        }
        private static void paragraph(StringBuilder out,String label,String value){out.append("<p><b>").append(escape(label)).append("：</b>").append(escape(value).replace("\n","<br>")).append("</p>");}
        private static String escape(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        private static String shortRevision(String revision){return revision==null?"无":revision.substring(0,Math.min(8,revision.length()));}
        private static String rootName(String root){if(root==null)return "未知仓库";try{return Path.of(root).getFileName().toString();}catch(RuntimeException e){return root;}}
        private void export(){
            Review.Report r=history.getSelectedValue();if(r==null)return;Reports.Resolution resolution=Reports.resolution(r.id(),view.resolutions());
            JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("silent-review-"+rootName(r.root())+"-"+shortRevision(r.target())+".md"));
            if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){
                Path output=chooser.getSelectedFile().toPath();
                ApplicationManager.getApplication().executeOnPooledThread(()->{try{Files.writeString(output,Reports.markdown(r,resolution),StandardCharsets.UTF_8);}catch(Exception e){showError("报告导出失败",e);}});
            }
        }
        private void scopeDiff(){
            if(disposed)return;
            int row=scopeTable.getSelectedRow();if(row<0)return;
            WorkspaceScope.File file=scopeModel.rows.get(scopeTable.convertRowIndexToModel(row));
            WorkspaceScope.Repository repository=view.scope().repositories().stream().filter(r->r.root().equals(file.root())).findFirst().orElse(null);if(repository==null)return;
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    if(disposed||project.isDisposed())return;
                    GitRepository.ChangedFile change=service.scopeFile(file.root(),repository.head(),file.path());
                    SwingUtilities.invokeLater(()->{
                        if(disposed||project.isDisposed())return;
                        var factory=DiffContentFactory.getInstance();
                        DiffManager.getInstance().showDiff(project,new SimpleDiffRequest(rootName(file.root())+" / "+file.path()+"（审查提交快照，不含当前编辑）",factory.create(project,change.before(),PlainTextFileType.INSTANCE),factory.create(project,change.after(),PlainTextFileType.INSTANCE),"基线快照 "+shortRevision(repository.baseline())+" / "+change.beforePath(),"目标快照 "+shortRevision(repository.head())+" / "+change.path()));
                    });
                }catch(Exception e){showError("无法打开范围 Diff",e);}
            });
        }
        private void locate(int index){
            if(disposed)return;
            Review.Report r=history.getSelectedValue();if(r==null||index<0||index>=r.result().findings().size())return;
            Review.Finding f=r.result().findings().get(index);
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    if(disposed||project.isDisposed())return;
                    Path root=Path.of(r.root()).toAbsolutePath().normalize();
                    var registeredRoots=git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories().stream().map(repo->Path.of(repo.getRoot().getPath())).toList();
                    List<Path> projectDirectories=new ArrayList<>();
                    if(project.getBasePath()!=null)projectDirectories.add(Path.of(project.getBasePath()));
                    for(var contentRoot:ProjectRootManager.getInstance(project).getContentRoots())projectDirectories.add(Path.of(contentRoot.getPath()));
                    if(!FindingNavigation.inProject(root,registeredRoots,projectDirectories))
                        throw new IllegalStateException("报告仓库不属于当前项目："+root+"。仅允许已登记的 Git 根，或项目根/内容根内自带 .git 的仓库；可选择查看提交快照。");
                    FindingNavigation.Target target=FindingNavigation.currentFile(root,f.filePath(),r.previousPaths());
                    var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path());
                    if(file==null)throw new IllegalStateException("IDE 未找到当前源码文件："+target.path()+"。可选择查看提交快照。");
                    SwingUtilities.invokeLater(()->{
                        if(disposed||project.isDisposed())return;
                        try{
                            if(!file.isValid()||file.isDirectory())throw new IllegalStateException("源码文件已删除或失效，可选择查看提交快照。");
                            var document=FileDocumentManager.getInstance().getDocument(file);
                            if(document==null)throw new IllegalStateException("文件不是可编辑的文本源码，可选择查看提交快照。");
                            FindingNavigation.Line line=FindingNavigation.currentLine(document.getText(),f.evidence(),f.startLine());
                            new OpenFileDescriptor(project,file,line.zeroBasedLine(),0).navigate(true);
                            String note=(target.renamedFallback()?"按同一仓库内的重命名路径打开。":"")+line.message();
                            if(line.match()==FindingNavigation.Match.APPROXIMATE)Messages.showWarningDialog(project,note,"源码已变化，定位仅供参考");
                            else if(!note.isBlank()){status.setText(note);status.setToolTipText(note);}
                        }catch(RuntimeException error){showError("无法打开项目源码",error);}
                    });
                }catch(Exception e){showError("无法打开项目源码",e);}
            });
        }
        private void snapshot(int index){
            if(disposed)return;
            Review.Report r=history.getSelectedValue();if(r==null||index<0||index>=r.result().findings().size())return;
            Review.Finding f=r.result().findings().get(index);
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    GitRepository git=new GitRepository(Path.of(r.root()),()->disposed||project.isDisposed());
                    String path=f.side().equals("before")&&r.previousPaths()!=null?r.previousPaths().getOrDefault(f.filePath(),f.filePath()):f.filePath();
                    String revision=f.side().equals("before")?r.base():r.target();String source=git.run("show",revision+":"+path);
                    SwingUtilities.invokeLater(()->{if(disposed||project.isDisposed())return;
                        LightVirtualFile file=new LightVirtualFile(Path.of(path).getFileName()+"@"+shortRevision(revision)+" ["+rootName(r.root())+"]",PlainTextFileType.INSTANCE,source);file.setWritable(false);
                        new OpenFileDescriptor(project,file,f.startLine()-1,0).navigate(true);
                    });
                }catch(Exception e){showError("无法打开历史代码",e);}
            });
        }
        private void showError(String message,Exception error){SwingUtilities.invokeLater(()->{if(!disposed&&!project.isDisposed())Messages.showErrorDialog(project,message+"："+error.getMessage(),"静默代码检测");});}
        private final class ScopeTable extends AbstractTableModel {
            private List<WorkspaceScope.File> rows=List.of();
            private final String[] columns={"纳入","仓库","冻结提交","文件","匹配规则 / 排除原因"};
            @Override public int getRowCount(){return rows.size();}
            @Override public int getColumnCount(){return columns.length;}
            @Override public String getColumnName(int column){return columns[column];}
            @Override public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}
            @Override public boolean isCellEditable(int row,int column){return column==0&&!view.busy()&&!view.scope().preparing()&&!view.scope().stale()&&rows.get(row).selectable();}
            @Override public Object getValueAt(int row,int column){
                WorkspaceScope.File f=rows.get(row);
                return switch(column){
                    case 0->f.included();case 1->f.root();
                    case 2->view.scope().repositories().stream().filter(r->r.root().equals(f.root())).map(r->shortRevision(r.head())).findFirst().orElse("");
                    case 3->f.changeType()+" "+f.path();
                    default->!f.selectable()?f.unavailableReason():String.join(", ",f.ruleIds())+(f.included()?" · 已纳入":" · 手动排除");
                };
            }
            @Override public void setValueAt(Object value,int row,int column){if(isCellEditable(row,column))service.setIncluded(rows.get(row).root(),rows.get(row).path(),Boolean.TRUE.equals(value));}
        }
        @Override public void dispose(){disposed=true;}
    }
}
