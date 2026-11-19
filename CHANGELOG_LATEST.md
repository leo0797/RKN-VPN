Что нового в 1.0.18:

• Если выбранный узел не отвечает, приложение проверяет до трёх запасных узлов через API ядра и переключается на первый рабочий. В настройках маршрутизации появился включатель «Автопереключение узла». Чтобы не было циклов, каждый узел проверяется не более одного раза до явного отключения или смены узла вручную.

What's new in 1.0.18:

• If the selected node does not respond, the app checks up to three fallback nodes through the core API and switches to the first working one. A new "Automatic node switching" toggle is available in routing settings. Each node is tried at most once until you disconnect or pick a node manually, so there are no loops.
